package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.download.DownloaderClient;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class UpgradeCommitService {
    private static final ReentrantLock COMMIT=UpgradeFileCoordinator.LOCK;
    private final UpgradeRepository repository;
    private final LibraryPathMappingService paths;
    private final SpaceReservationService space;
    private final ChapterCommitLockService chapterLocks=new ChapterCommitLockService();
    public UpgradeCommitService(UpgradeRepository repository,LibraryPathMappingService paths,SpaceReservationService space) {
        this.repository=repository; this.paths=paths; this.space=space;
    }
    public static boolean committing() { return COMMIT.isLocked(); }
    public void saveJob(Job job) {
        if(repository.cancelRequested(job.getId())&&!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING,JobState.COMPLETED).contains(job.getState())) {
            job.setState(JobState.CANCELLED); job.setMessage("已取消，保留已下载文件");
        }
        repository.save("upgrade_jobs",job.getId(),job.getTargetId(),job.getIdentityKey(),job.getState().name(),1,job);
    }
    private void saveFile(JobFile file) { repository.save("upgrade_job_files",file.getId(),file.getJobId(),null,file.getState(),1,file); }

    public void prepare(Job job,Target target,List<JobFile> incoming,List<Version> oldVersions) throws IOException {
        if(repository.cancelRequested(job.getId())) throw new IOException("CANCEL_REQUESTED");
        if(job.getState()!=JobState.VERIFYING) throw new IllegalStateException("提交清单只能由已校验任务建立");
        List<Version> expanded=new ArrayList<>(oldVersions);
        Set<String> replacingVideos=oldVersions.stream().map(Version::getId).collect(java.util.stream.Collectors.toSet());
        for(Version subtitle:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) {
            if(!"SUBTITLE".equals(subtitle.getKind())||subtitle.isProtectedVersion()||subtitle.getPath()==null) continue;
            boolean exclusive=!subtitle.getRelatedVersionIds().isEmpty()&&replacingVideos.containsAll(subtitle.getRelatedVersionIds());
            if(subtitle.getRelatedVersionIds().isEmpty()) exclusive=oldVersions.stream().anyMatch(video->subtitle.getPath().startsWith(video.getPath().replaceFirst("\\.[^.]+$","")+"."));
            if(exclusive) expanded.add(subtitle);
        }
        oldVersions=expanded;
        for(Version old:oldVersions) {
            new EmbyLibraryIndexService(repository,paths,new ReleaseAnalysisService()).validateAdopted(old);
            if(repository.all("upgrade_versions",null,"ACTIVE",Version.class).stream().anyMatch(v->Objects.equals(v.getPath(),old.getPath())&&(v.isProtectedVersion()||!Objects.equals(v.getTargetId(),target.getId())))) throw new IOException("OLD_PATH_SHARED_OR_PROTECTED");
            Set<String> coverage=new TreeSet<>();
            for(JobFile file:incoming) {
                Release next=file.getAttributes(),previous=old.getAttributes();
                if(Objects.equals(next.getSubjectId(),previous.getSubjectId())&&Objects.equals(next.getSeason(),previous.getSeason())
                        &&Objects.equals(next.getChapterType(),previous.getChapterType())&&Objects.equals(next.getContentEdition(),previous.getContentEdition())) coverage.addAll(next.getEpisodes());
            }
            if(old.isProtectedVersion()||!coverage.containsAll(old.getAttributes().getEpisodes())) throw new IOException("OLD_COVERAGE_NOT_REPLACED");
        }
        String root=target.getOutputRoot();
        if(root==null||root.isBlank()) throw new IOException("OUTPUT_ROOT_REQUIRED");
        Path outputRoot=Path.of(root).toAbsolutePath().normalize();
        if(target.getOutputDirectory()!=null) outputRoot=allowedRoot(Path.of(target.getOutputDirectory()),ConfigUtil.snapshot().getUpgrade());
        if(!Files.isDirectory(outputRoot)) throw new IOException("OUTPUT_ROOT_NOT_ACCESSIBLE");
        repository.resetPlannedFiles(job.getId()); job.getVersionIds().clear();
        for(JobFile file:incoming) {
            file.setId(UUID.randomUUID().toString()); file.setJobId(job.getId()); file.setAction("INSTALL"); file.setAllowedRoot(outputRoot.toString());
            Path destination=Path.of(file.getDestination()).toAbsolutePath().normalize();
            paths.requireSafe(destination,outputRoot,false);
            if(Files.exists(destination)) throw new IOException("DESTINATION_ALREADY_EXISTS");
            file.setDestination(destination.toString()); file.setTemporary(destination.resolveSibling(destination.getFileName()+".install-"+job.getId()).toString());
            file.setVersionId(UUID.randomUUID().toString()); saveFile(file);
        }
        for(Version old:oldVersions) {
            if(old.getPath()==null) throw new IOException("OLD_PATH_UNAVAILABLE");
            Path oldPath=Path.of(old.getPath());
            Path allowed=allowedRoot(oldPath,ConfigUtil.snapshot().getUpgrade());
            paths.requireSafe(oldPath,allowed,true);
            var attributes=Files.readAttributes(oldPath,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(old.getSize()!=attributes.size()||old.getModifiedAt()!=attributes.lastModifiedTime().toMillis()) throw new IOException("OLD_FILE_CHANGED");
            JobFile deletion=new JobFile(); deletion.setId(UUID.randomUUID().toString()); deletion.setJobId(job.getId()); deletion.setAction("DELETE_OLD");
            deletion.setSource(oldPath.toString()); deletion.setAllowedRoot(allowed.toString()); deletion.setVersionId(old.getId());
            deletion.setSize(attributes.size()); deletion.setModifiedAt(attributes.lastModifiedTime().toMillis()); deletion.setFileKey(Objects.toString(attributes.fileKey(),null));
            deletion.setSha256(MediaVerificationService.sha256(oldPath)); deletion.setAttributes(old.getAttributes()); saveFile(deletion);
            job.getVersionIds().add(old.getId());
            if("AUTOMATIC_UPGRADE".equals(old.getOrigin())&&old.getSourcePath()!=null&&!old.getSourcePath().equals(old.getPath())) {
                Set<String> replacing=oldVersions.stream().map(Version::getId).collect(java.util.stream.Collectors.toSet());
                boolean referenced=repository.all("upgrade_versions",null,"ACTIVE",Version.class).stream().anyMatch(v->!replacing.contains(v.getId())&&(Objects.equals(v.getSourcePath(),old.getSourcePath())||Objects.equals(v.getPath(),old.getSourcePath())));
                Path sourcePath=Path.of(old.getSourcePath()); String staging=ConfigUtil.snapshot().getUpgrade().getStagingRoot();
                if(!referenced&&staging!=null&&!staging.isBlank()&&Files.isRegularFile(sourcePath,LinkOption.NOFOLLOW_LINKS)&&sourcePath.toAbsolutePath().normalize().startsWith(Path.of(staging).toAbsolutePath().normalize())) {
                    paths.requireSafe(sourcePath,Path.of(staging),true); var a=Files.readAttributes(sourcePath,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    JobFile sourceDelete=new JobFile(); sourceDelete.setId(UUID.randomUUID().toString()); sourceDelete.setJobId(job.getId()); sourceDelete.setAction("DELETE_SOURCE"); sourceDelete.setSource(sourcePath.toString()); sourceDelete.setAllowedRoot(staging); sourceDelete.setVersionId(old.getId()); sourceDelete.setSize(a.size()); sourceDelete.setModifiedAt(a.lastModifiedTime().toMillis()); sourceDelete.setFileKey(Objects.toString(a.fileKey(),null)); sourceDelete.setSha256(MediaVerificationService.sha256(sourcePath)); sourceDelete.setAttributes(old.getAttributes()); saveFile(sourceDelete);
                } else repository.event(job.getId(),"SOURCE_RETAINED","旧媒体链接将删除，共享下载源或未授权源路径保留");
            }
        }
        job.setBaselineFingerprint(baseline(target,incoming));
        job.setState(JobState.READY_TO_COMMIT); saveJob(job);
    }
    /** Only media identity and decision fields participate; scan timestamps and evidence do not. */
    private String baseline(Target target,List<JobFile> incoming) {
        List<Map<String,Object>> snapshot=new ArrayList<>();
        for(Version version:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) {
            Release old=version.getAttributes();
            if(old==null||!"VIDEO".equals(version.getKind())||incoming.stream().map(JobFile::getAttributes).filter(Objects::nonNull).noneMatch(next->
                    Objects.equals(next.getSubjectId(),old.getSubjectId())&&Objects.equals(next.getSeason(),old.getSeason())
                    &&Objects.equals(next.getChapterType(),old.getChapterType())&&Objects.equals(next.getContentEdition(),old.getContentEdition())
                    &&!Collections.disjoint(next.getEpisodes(),old.getEpisodes()))) continue;
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",version.getId()); row.put("path",version.getPath()); row.put("size",version.getSize()); row.put("mtime",version.getModifiedAt()); row.put("fileKey",version.getFileKey()); row.put("sha256",version.getSha256());
            row.putAll(comparisonFields(old));
            snapshot.add(row);
        }
        snapshot.sort(Comparator.comparing(row->Objects.toString(row.get("id"),"")));
        return cn.hutool.crypto.SecureUtil.sha256(GsonStatic.toJson(snapshot));
    }
    private static Map<String,Object> comparisonFields(Release release) {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("subject",release.getSubjectId()); row.put("season",release.getSeason()); row.put("chapters",new TreeSet<>(release.getEpisodes())); row.put("kind",release.getChapterType()); row.put("edition",release.getContentEdition());
        row.put("resolution",release.getResolution()); row.put("groups",new TreeSet<>(release.getGroups())); row.put("languages",new TreeSet<>(release.getLanguages())); row.put("layout",release.getSubtitleLayout()); row.put("packaging",release.getSubtitlePackaging()); row.put("source",release.getSourceType()); row.put("codec",release.getCodec()); row.put("container",release.getContainer()); row.put("correction",release.getCorrection());
        return row;
    }
    public String comparisonFingerprint(Target target,QualityPolicyEngine.Policy policy,Release release) {
        JobFile view=new JobFile(); view.setAttributes(release);
        List<Map<String,Object>> protection=repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).stream()
                .filter(v->"VIDEO".equals(v.getKind())&&v.getAttributes()!=null&&Objects.equals(v.getAttributes().getSubjectId(),release.getSubjectId())
                        &&Objects.equals(v.getAttributes().getSeason(),release.getSeason())&&Objects.equals(v.getAttributes().getChapterType(),release.getChapterType())
                        &&Objects.equals(v.getAttributes().getContentEdition(),release.getContentEdition())&&!Collections.disjoint(v.getAttributes().getEpisodes(),release.getEpisodes()))
                .sorted(Comparator.comparing(Version::getId)).map(v->Map.<String,Object>of("id",v.getId(),"protected",v.isProtectedVersion())).toList();
        return cn.hutool.crypto.SecureUtil.sha256(GsonStatic.toJson(Map.of("baseline",baseline(target,List.of(view)),"candidate",comparisonFields(release),
                "policyId",Objects.toString(policy.getId(),""),"policyRevision",policy.getRevision(),"targetRevision",target.getRevision(),"releaseRevision",release.getRevision(),"protection",protection)));
    }
    public static Path allowedRoot(Path path,UpgradeSettings settings) throws IOException {
        List<Path> roots=new ArrayList<>();
        for(var mapping:settings.getEmbyMappings()) roots.add(Path.of(mapping.localRoot()).toAbsolutePath().normalize());
        for(String root:List.of(settings.getTvRoot(),settings.getMovieRoot())) if(root!=null&&!root.isBlank()) roots.add(Path.of(root).toAbsolutePath().normalize());
        return roots.stream().filter(path.toAbsolutePath().normalize()::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElseThrow(()->new IOException("OLD_FILE_OUTSIDE_LIBRARY_ROOTS"));
    }

    public void commit(Job job,Target target) throws IOException {
        if(!COMMIT.tryLock()) throw new IOException("COMMIT_BUSY");
        try {
            Set<JobState> recoveringStates=Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING);
            if(!recoveringStates.contains(job.getState())&&repository.all("upgrade_jobs",null,null,Job.class).stream().anyMatch(j->!Objects.equals(j.getId(),job.getId())&&recoveringStates.contains(j.getState()))) throw new IOException("PREVIOUS_COMMIT_RECOVERY_PENDING");
            List<JobFile> files=repository.all("upgrade_job_files",job.getId(),null,JobFile.class);
            List<JobFile> installs=files.stream().filter(f->"INSTALL".equals(f.getAction())).toList();
            if(installs.isEmpty()) throw new IOException("INSTALL_MANIFEST_EMPTY");
            boolean installed=Set.of(JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState());
            if(!installed) {
                boolean recovering=job.getState()==JobState.INSTALLING;
                var config=ConfigUtil.snapshot();
                if(!recovering&&(repository.cancelRequested(job.getId())||config.getUpgradeMode()!=UpgradeMode.AUTOMATIC||!Objects.equals(config.getUpgradeModeRevision(),job.getGeneration()))) throw new IOException("MODE_OR_CONFIGURATION_CHANGED");
                if(!recovering) {
                    var policy=repository.get("upgrade_policies",job.getPolicyId(),QualityPolicyEngine.Policy.class).orElseThrow();
                    Target latest=repository.get("upgrade_targets",target.getId(),Target.class).orElseThrow();
                    if(policy.getRevision()!=job.getPolicyRevision()||latest.isExcluded()||job.getTargetRevision()>0&&latest.getRevision()!=job.getTargetRevision()) throw new IOException("POLICY_OR_TARGET_CHANGED");
                    if(!Objects.equals(job.getBaselineFingerprint(),baseline(latest,installs))) {
                        job.setState(JobState.VERIFYING); job.setReasonCode("BASELINE_CHANGED"); job.setMessage("已有版本发生变化，重新校验已下载资源与当前基线"); job.setRetryAt(0); job.setRetryable(true); saveJob(job);
                        chapterLocks.release(job.getId()); throw new IOException("BASELINE_CHANGED");
                    }
                }
                if(!chapterLocks.acquire(job,target,installs,System.currentTimeMillis())) throw new IOException("CHAPTER_COMMIT_BUSY");
                job.setState(JobState.INSTALLING); saveJob(job);
                for(JobFile file:installs) install(file);
                List<Version> installedVersions=new ArrayList<>();
                for(JobFile file:installs) {
                        Version version=new Version(); version.setId(file.getVersionId()); version.setTargetId(target.getId()); version.setLibraryId(target.getLibraryId());
                        version.setPath(file.getDestination()); version.setOrigin("AUTOMATIC_UPGRADE"); version.setAttributes(file.getAttributes());
                        version.setKind(file.getKind()); version.setSourcePath(file.getSource());
                        repository.get("upgrade_releases",job.getReleaseId(),Release.class).ifPresent(r->version.setDownloadHash(r.getInfoHash())); version.setDownloaderType(job.getDownloaderType());
                        try {
                            var actual=Files.readAttributes(Path.of(file.getDestination()),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                            version.setModifiedAt(actual.lastModifiedTime().toMillis()); version.setFileKey(Objects.toString(actual.fileKey(),null));
                        } catch(IOException e) { throw new IOException("安装文件无法读取",e); }
                        version.setSize(file.getSize()); version.setSha256(file.getSha256()); version.setAccessible(true);
                        installedVersions.add(version);
                }
                for(Version subtitle:installedVersions) if("SUBTITLE".equals(subtitle.getKind())) for(Version video:installedVersions) {
                    if("VIDEO".equals(video.getKind())&&subtitle.getPath().startsWith(video.getPath().replaceFirst("\\.[^.]+$","")+".")) subtitle.getRelatedVersionIds().add(video.getId());
                }
                DatabaseManager.transaction(c->{
                    for(Version version:installedVersions) {
                        repository.save("upgrade_versions",version.getId(),target.getId(),null,"ACTIVE",1,version);
                    }
                    job.setState(JobState.INSTALLED); saveJob(job); return null;
                });
                repository.event(job.getId(),"INSTALLED","已验证新版安装完成，开始精确清理旧版");
            }
            if(installed&&!chapterLocks.acquire(job,target,installs,System.currentTimeMillis())) throw new IOException("CHAPTER_COMMIT_BUSY");
            cleanup(job);
        } finally { if(job.getState()==JobState.COMPLETED) chapterLocks.release(job.getId()); space.release("install:"+job.getId()); COMMIT.unlock(); }
    }

    private void install(JobFile file) throws IOException {
        Path source=Path.of(file.getSource()), destination=Path.of(file.getDestination()), temporary=Path.of(file.getTemporary()), root=Path.of(file.getAllowedRoot());
        paths.requireSafe(destination,root,false); paths.requireSafe(temporary,root,false);
        Path sourceRoot=UpgradePaths.parent(source.toAbsolutePath()); paths.requireSafe(source,sourceRoot,true);
        if(Files.exists(destination)) {
            if(!MediaVerificationService.sha256(destination).equals(file.getSha256())) throw new IOException("DESTINATION_CONFLICT");
            file.setState("INSTALLED"); file.setError(null); saveFile(file); return;
        }
        if(!MediaVerificationService.sha256(source).equals(file.getSha256())) throw new IOException("VERIFIED_SOURCE_CHANGED");
        Files.createDirectories(UpgradePaths.parent(destination)); paths.requireSafe(destination,root,false);
        if(Files.exists(temporary)&&"COPY_INCOMPLETE".equals(file.getState())) {
            paths.requireSafe(temporary,root,true);
            if(!UpgradePaths.fileName(temporary).equals(destination.getFileName()+".install-"+file.getJobId())) throw new IOException("INSTALL_TEMPORARY_IDENTITY_CONFLICT");
            // Recover one explicitly journalled partial copy; no directory cleanup.
            Files.delete(temporary); file.setState("PLANNED"); file.setError(null); saveFile(file);
        }
        if(!Files.exists(temporary)) {
            try { space.reserve("install:"+file.getJobId(),UpgradePaths.parent(destination),0,ConfigUtil.snapshot().getUpgrade().getReserveBytes()); Files.createLink(temporary,source); }
            catch(IOException|UnsupportedOperationException e) {
                space.reserve("install:"+file.getJobId(),UpgradePaths.parent(destination),file.getSize(),ConfigUtil.snapshot().getUpgrade().getReserveBytes());
                // CREATE_NEW proves ownership before journalling the exact temporary file.
                // A write failure or process restart can then recover on the first retry.
                try(var output=Files.newOutputStream(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                    file.setState("COPY_INCOMPLETE"); file.setError("INSTALL_COPY_INCOMPLETE"); saveFile(file);
                    try(var input=Files.newInputStream(source)) { input.transferTo(output); }
                } catch(IOException copyError) {
                    if("COPY_INCOMPLETE".equals(file.getState())) throw new IOException("INSTALL_COPY_INCOMPLETE",copyError);
                    throw copyError;
                }
            }
        }
        if(Files.size(temporary)!=file.getSize()||!MediaVerificationService.sha256(temporary).equals(file.getSha256())) {
            if(!"COPY_INCOMPLETE".equals(file.getState())) throw new IOException("INSTALL_TEMPORARY_IDENTITY_CONFLICT");
            file.setState("COPY_INCOMPLETE"); file.setError("INSTALL_COPY_INCOMPLETE"); saveFile(file); throw new IOException("INSTALL_COPY_INCOMPLETE");
        }
        Files.move(temporary,destination);
        if(!Files.isReadable(destination)) throw new IOException("INSTALLED_FILE_NOT_READABLE");
        file.setState("INSTALLED"); file.setError(null); saveFile(file);
    }
    private void cleanup(Job job) throws IOException {
        List<JobFile> files=repository.all("upgrade_job_files",job.getId(),null,JobFile.class);
        for(JobFile f:files) if("INSTALL".equals(f.getAction())&&(!Files.isReadable(Path.of(f.getDestination()))
                ||!MediaVerificationService.sha256(Path.of(f.getDestination())).equals(f.getSha256()))) throw new IOException("INSTALLED_VERSION_CHANGED");
        job.setState(JobState.CLEANING_OLD); saveJob(job);
        try {
            for(JobFile file:files) {
                if(!Set.of("DELETE_OLD","DELETE_SOURCE").contains(file.getAction())||"DELETED".equals(file.getState())) continue;
                if("DELETE_OLD".equals(file.getAction())&&repository.all("upgrade_versions",null,"ACTIVE",Version.class).stream().anyMatch(v->Objects.equals(v.getPath(),file.getSource())&&(v.isProtectedVersion()||!Objects.equals(v.getTargetId(),job.getTargetId())))) throw new IOException("OLD_PATH_SHARED_OR_PROTECTED");
                if("DELETE_SOURCE".equals(file.getAction())) {
                    boolean referenced=repository.all("upgrade_versions",null,"ACTIVE",Version.class).stream().anyMatch(v->!job.getVersionIds().contains(v.getId())&&(Objects.equals(v.getSourcePath(),file.getSource())||Objects.equals(v.getPath(),file.getSource())));
                    if(referenced) { file.setState("RETAINED_SHARED_SOURCE"); saveFile(file); continue; }
                }
                Path old=Path.of(file.getSource());
                if(Files.exists(old,LinkOption.NOFOLLOW_LINKS)) {
                    paths.requireSafe(old,Path.of(file.getAllowedRoot()),true);
                    var a=Files.readAttributes(old,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(a.size()!=file.getSize()||a.lastModifiedTime().toMillis()!=file.getModifiedAt()
                            ||!Objects.equals(Objects.toString(a.fileKey(),null),file.getFileKey())
                            ||!MediaVerificationService.sha256(old).equals(file.getSha256())) throw new IOException("OLD_FILE_CHANGED");
                    stopAssociatedTasks(file);
                    paths.requireSafe(old,Path.of(file.getAllowedRoot()),true);
                    var stopped=Files.readAttributes(old,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(stopped.size()!=file.getSize()||stopped.lastModifiedTime().toMillis()!=file.getModifiedAt()||!Objects.equals(Objects.toString(stopped.fileKey(),null),file.getFileKey())||!MediaVerificationService.sha256(old).equals(file.getSha256())) throw new IOException("OLD_FILE_CHANGED_AFTER_STOP");
                    // One exact regular file per manifest step. Never recursive,
                    // never downloader deleteFiles, never item-level deletion.
                    Files.delete(old);
                }
                file.setState("DELETED"); file.setError(null); saveFile(file);
                if("DELETE_OLD".equals(file.getAction())) repository.get("upgrade_versions",file.getVersionId(),Version.class).ifPresent(v->{ v.setActive(false); repository.save("upgrade_versions",v.getId(),v.getTargetId(),null,"RETIRED",1,v); });
            }
            job.setState(JobState.COMPLETED); job.setReasonCode("UPGRADE_COMPLETE"); job.setMessage("新版已安装，清单内旧文件已全部删除"); job.setRetryable(false); saveJob(job);
            repository.event(job.getId(),"UPGRADE_COMPLETE",job.getMessage());
        } catch(IOException|RuntimeException e) {
            job.setState(JobState.CLEANUP_PENDING); job.setReasonCode("OLD_CLEANUP_FAILED"); job.setMessage("新版可用，旧文件清理待重试："+Objects.toString(e.getMessage(),"IO_ERROR")); job.setRetryable(true); saveJob(job);
            throw e;
        }
    }
    private void stopAssociatedTasks(JobFile file) throws IOException {
        Version version=repository.get("upgrade_versions",file.getVersionId(),Version.class).orElse(null);
        if((ConfigUtil.snapshot().getDownloadToolHost()==null||ConfigUtil.snapshot().getDownloadToolHost().isBlank())&&version!=null&&"EMBY_ADOPTED".equals(version.getOrigin())&&version.getDownloadHash()==null) return;
        DownloaderClient client=TorrentUtil.client();
        if(client==null) throw new IOException("DOWNLOADER_UNAVAILABLE_FOR_CLEANUP");
        var result=client.inventory();
        if(!result.isSuccess()) throw new IOException("DOWNLOADER_SNAPSHOT_FAILED");
        UpgradeSettings settings=ConfigUtil.snapshot().getUpgrade();
        for(TorrentsInfo task:result.value()) {
            boolean associated=version!=null&&version.getDownloadHash()!=null&&version.getDownloadHash().equalsIgnoreCase(task.getHash());
            if(!associated&&task.getFilesSupplier()!=null) {
                for(String remote:task.getFilesSupplier().get()) {
                    String combined=Path.of(remote).isAbsolute()?remote:task.getSavePath()+"/"+remote;
                    try { if(paths.map(combined,settings.getDownloaderMappings()).equals(Path.of(file.getSource()))) associated=true; }
                    catch(IllegalArgumentException ignored) { }
                }
            }
            if(associated&&!client.delete(task,false).isSuccess()) throw new IOException("OLD_TASK_STOP_FAILED");
        }
    }
}
