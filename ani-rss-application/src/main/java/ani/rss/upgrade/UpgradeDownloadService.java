package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.download.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class UpgradeDownloadService {
    private final UpgradeRepository repository;
    private final LibraryPathMappingService paths;
    private final MediaVerificationService verification;
    private final ReleaseAnalysisService analysis;
    private final SpaceReservationService space;
    public UpgradeDownloadService(UpgradeRepository repository,LibraryPathMappingService paths,MediaVerificationService verification,ReleaseAnalysisService analysis,SpaceReservationService space) {
        this.repository=repository; this.paths=paths; this.verification=verification; this.analysis=analysis; this.space=space;
    }
    public static String downloaderIdentity(ani.rss.entity.Config config) {
        return cn.hutool.crypto.SecureUtil.sha256(config.getDownloadToolType()+"\n"+config.getDownloadToolHost()+"\n"+config.getDownloadToolUsername());
    }
    public TorrentsInfo submit(Job job,Release release) throws IOException {
        var config=ConfigUtil.snapshot(); UpgradeSettings s=config.getUpgrade();
        if(config.getUpgradeMode()!=UpgradeMode.AUTOMATIC||!Objects.equals(config.getUpgradeModeRevision(),job.getGeneration())) throw new IOException("MODE_CHANGED");
        if(s.getStagingRoot()==null||s.getStagingRoot().isBlank()) throw new IOException("STAGING_ROOT_REQUIRED");
        Path staging=Path.of(s.getStagingRoot()).toAbsolutePath().normalize();
        if(!Files.isDirectory(staging)) throw new IOException("STAGING_ROOT_NOT_ACCESSIBLE");
        for(var mapping:s.getEmbyMappings()) {
            Path library=Path.of(mapping.localRoot()).toAbsolutePath().normalize();
            if(staging.startsWith(library)||library.startsWith(staging)) throw new IOException("STAGING_OVERLAPS_LIBRARY");
        }
        for(String output:List.of(s.getTvRoot(),s.getMovieRoot())) if(output!=null&&!output.isBlank()) {
            Path library=Path.of(output).toAbsolutePath().normalize();
            if(staging.startsWith(library)||library.startsWith(staging)) throw new IOException("STAGING_OVERLAPS_LIBRARY");
        }
        DownloaderClient client=TorrentUtil.client(); if(client==null||!client.connect(false).isSuccess()) throw new IOException("DOWNLOADER_CONNECTION_FAILED");
        var snapshot=client.inventory(); if(!snapshot.isSuccess()) throw new IOException("DOWNLOADER_SNAPSHOT_FAILED");
        for(TorrentsInfo task:snapshot.value()) if(release.getInfoHash()!=null&&release.getInfoHash().equalsIgnoreCase(task.getHash())) {
            // Same-hash ordinary downloads may supply read-only transfer input.
            // They retain ownership and never receive automatic rename/delete.
            job.setRemoteTaskId(taskReference(task,config.getDownloadToolType())); job.setStagingPath(task.getSavePath()); job.setDownloaderType(config.getDownloadToolType()); job.setDownloaderIdentity(downloaderIdentity(config)); return task;
        }
        long active=snapshot.value().stream().filter(t->!t.finished()).count();
        int maximum=config.getDownloadCount()==null?0:config.getDownloadCount();
        if(maximum>0&&active>=maximum) throw new IOException("GLOBAL_DOWNLOAD_LIMIT");
        Path local=staging.resolve(".ani-rss-upgrade").resolve(job.getId());
        paths.requireSafe(local,staging,false);
        space.reserve("download:"+job.getId(),staging,release.getExpectedBytes(),s.getReserveBytes());
        Files.createDirectories(local);
        job.setStagingPath(local.toString()); job.setDownloaderType(config.getDownloadToolType()); job.setDownloaderIdentity(downloaderIdentity(config));
        job.setReservedBytes(release.getExpectedBytes());
        repository.save("upgrade_jobs",job.getId(),job.getTargetId(),job.getIdentityKey(),job.getState().name(),1,job);
        String remote=remotePath(local,s);
        java.io.File torrent=null;
        if(release.getTorrentUrl()!=null&&!release.getTorrentUrl().startsWith("magnet:")) {
            String url=release.getTorrentUrl(); UpgradeHttp.base(url.contains("?")?url.substring(0,url.indexOf('?')):url);
            try(var response=ani.rss.util.basic.HttpReq.get(url).setFollowRedirects(false).executeAsync()) {
                if(response.getStatus()!=200) throw new IOException("TORRENT_FETCH_FAILED");
                byte[] bytes=response.bodyStream().readNBytes(16*1024*1024+1); if(bytes.length>16*1024*1024) throw new IOException("TORRENT_TOO_LARGE");
                Path file=local.resolve("input.torrent");
                if(!Files.exists(file)) Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
                torrent=file.toFile(); String hash=TorrentUtil.getCanonicalInfoHash(torrent);
                if(hash==null||release.getInfoHash()!=null&&!release.getInfoHash().equalsIgnoreCase(hash)) throw new IOException("TORRENT_HASH_CONFLICT");
                release.setInfoHash(hash);
            }
        }
        if(release.getInfoHash()==null) throw new IOException("CANONICAL_HASH_REQUIRED");
        new UpgradeOwnershipService().pending(job,release,remote);
        DownloadSubmission submission=new DownloadSubmission(DownloadSubmission.OwnerKind.UPGRADE_TARGET,job.getTargetId(),job.getId(),release.getInfoHash(),remote,release.getTitle(),release.getTorrentUrl(),torrent,release.getEpisodes());
        var result=client.submit(submission); if(!result.isSuccess()) throw new IOException(result.errorCode());
        job.setRemoteTaskId(result.remoteTaskId());
        new UpgradeOwnershipService().activate(job);
        return null;
    }
    private String remotePath(Path local,UpgradeSettings settings) {
        // map() uses native Path for its destination, so serialize foreign paths
        // lexically here to retain UNC/Linux roots on a Windows server.
        UpgradeSettings.PathMapping best=null; int length=-1;
        for(var m:settings.getDownloaderMappings()) {
            Path root=Path.of(m.localRoot()).toAbsolutePath().normalize();
            if(local.startsWith(root)&&root.getNameCount()>length) { best=m; length=root.getNameCount(); }
        }
        if(best==null) throw new IllegalArgumentException("暂存目录缺少下载器路径映射");
        String suffix=Path.of(best.localRoot()).toAbsolutePath().normalize().relativize(local).toString().replace('\\','/');
        return LibraryPathMappingService.normalizeRemote(best.remoteRoot())+"/"+suffix;
    }
    private static String taskReference(TorrentsInfo task,String downloaderType) {
        // qBittorrent exposes only the hash; Transmission accepts the same stable
        // hash despite returning a numeric ID in its inventory. Aria2 needs GIDs.
        if(("qBittorrent".equalsIgnoreCase(downloaderType)||"Transmission".equalsIgnoreCase(downloaderType))&&task.getHash()!=null&&!task.getHash().isBlank()) return task.getHash().toLowerCase(Locale.ROOT);
        return task.getId();
    }
    public Optional<TorrentsInfo> task(Job job,Release release) throws IOException {
        var config=ConfigUtil.snapshot();
        if(!Objects.equals(job.getDownloaderIdentity(),downloaderIdentity(config))) throw new IOException("DOWNLOADER_CONFIGURATION_CHANGED");
        DownloaderClient client=TorrentUtil.client(); if(client==null) throw new IOException("DOWNLOADER_UNAVAILABLE");
        var snapshot=client.inventory(); if(!snapshot.isSuccess()) throw new IOException("DOWNLOADER_SNAPSHOT_FAILED");
        Optional<TorrentsInfo> found=snapshot.value().stream().filter(t->release.getInfoHash()!=null&&release.getInfoHash().equalsIgnoreCase(t.getHash())
                ||job.getRemoteTaskId()!=null&&job.getRemoteTaskId().equals(t.getId())&&(release.getInfoHash()==null||t.getHash()==null)).findFirst();
        if(found.isPresent()&&!"ARIA2_METADATA".equals(found.get().getCategory())) job.setRemoteTaskId(taskReference(found.get(),config.getDownloadToolType()));
        return found;
    }
    public void stopOwned(Job job) throws IOException {
        if(job.getRemoteTaskId()==null) return;
        UpgradeOwnershipService ownership=new UpgradeOwnershipService();
        if(!ownership.owns(job)) { job.setRemoteTaskId(null); return; }
        Optional<Job> shared=repository.all("upgrade_jobs",null,null,Job.class).stream()
                .filter(other->!Objects.equals(job.getId(),other.getId())&&Objects.equals(job.getRemoteTaskId(),other.getRemoteTaskId())
                        &&Objects.equals(job.getDownloaderIdentity(),other.getDownloaderIdentity())
                        &&!Set.of(JobState.COMPLETED,JobState.CANCELLED,JobState.FAILED,JobState.NOT_BETTER).contains(other.getState())
                        &&!repository.cancelRequested(other.getId())).findFirst();
        if(shared.isPresent()) {
            Job survivor=shared.get(); survivor.setReservedBytes(Math.max(survivor.getReservedBytes(),job.getReservedBytes()));
            repository.save("upgrade_jobs",survivor.getId(),survivor.getTargetId(),survivor.getIdentityKey(),survivor.getState().name(),survivor.getPolicyRevision(),survivor);
            ownership.transfer(job,survivor); space.transfer("download:"+job.getId(),"download:"+survivor.getId());
            job.setReservedBytes(0); job.setRemoteTaskId(null); return;
        }
        var config=ConfigUtil.snapshot();
        if(!Objects.equals(job.getDownloaderIdentity(),downloaderIdentity(config))) throw new IOException("DOWNLOADER_CONFIGURATION_CHANGED");
        DownloaderClient client=TorrentUtil.client(); if(client==null) throw new IOException("DOWNLOADER_UNAVAILABLE");
        var snapshot=client.inventory(); if(!snapshot.isSuccess()) throw new IOException("DOWNLOADER_SNAPSHOT_FAILED");
        String ownedHash=ownership.infoHash(job).orElse(null);
        for(TorrentsInfo task:snapshot.value()) if((Objects.equals(job.getRemoteTaskId(),task.getId())||task.getHash()!=null&&task.getHash().equalsIgnoreCase(job.getRemoteTaskId()))
                &&(ownedHash==null||task.getHash()==null||ownedHash.equalsIgnoreCase(task.getHash()))) {
            if(!client.delete(task,false).isSuccess()) throw new IOException("OWNED_TRANSFER_STOP_FAILED");
        }
        ownership.stopped(job); job.setRemoteTaskId(null);
    }
    public List<JobFile> verify(Job job,Target target,Release release,TorrentsInfo task) throws IOException {
        if(!task.finished()||task.getFilesSupplier()==null) throw new IOException("DOWNLOAD_NOT_COMPLETE");
        UpgradeSettings settings=ConfigUtil.snapshot().getUpgrade();
        List<Path> videos=new ArrayList<>();
        List<Path> subtitles=new ArrayList<>();
        boolean unsupportedCarrier=false;
        for(String file:task.getFilesSupplier().get()) {
            String remote=file.matches("^[A-Za-z]:[/\\\\].*")||file.startsWith("/")?file:task.getSavePath()+"/"+file;
            Path path=paths.map(remote,settings.getDownloaderMappings());
            Path allowed=settings.getDownloaderMappings().stream().map(m->Path.of(m.localRoot()).toAbsolutePath().normalize())
                    .filter(path::startsWith).max(Comparator.comparingInt(Path::getNameCount)).orElseThrow(()->new IOException("DOWNLOAD_PATH_OUTSIDE_MAPPING"));
            paths.requireSafe(path,allowed,true);
            String name=UpgradePaths.fileName(path).toLowerCase(Locale.ROOT);
            if(name.matches(".*\\.(mkv|mp4|avi|webm|ts)$")) videos.add(path);
            if(name.matches(".*\\.(ass|ssa|srt|vtt)$")) subtitles.add(path);
            if(name.matches(".*\\.(iso|zip|rar|7z|m2ts|mpls)$")||name.equals("index.bdmv")) unsupportedCarrier=true;
        }
        if(videos.isEmpty()) throw new IOException(unsupportedCarrier?"UNSUPPORTED_RESOURCE_FORMAT":"NO_SUPPORTED_VIDEO_FILES");
        boolean scopedCollection=release.getEvidence().stream().anyMatch(e->"CHAPTER_SCOPE_FILTER".equals(e.getSource()));
        if(scopedCollection&&videos.size()==1) throw new IOException("BATCH_FILE_PARTIAL_CHAPTER_OVERLAP");
        List<JobFile> files=new ArrayList<>(); Set<String> coverage=new TreeSet<>();
        for(Path video:videos) {
            Release filename=analysis.parse(UpgradePaths.fileName(video),target.getSubjectId(),"TORRENT_FILE");
            SubjectEpisodeResolver.normalizeChapters(target,filename);
            if(scopedCollection&&Collections.disjoint(filename.getEpisodes(),release.getEpisodes())) continue;
            if("EXTRA".equals(filename.getChapterType())) continue;
            if(videos.size()>1&&(!"NORMAL".equals(filename.getChapterType())&&!filename.getChapterType().equals(release.getChapterType())||filename.getEpisodes().isEmpty())) {
                repository.event(job.getId(),"BATCH_FILE_IDENTITY_DEFERRED","合集中的一个文件章节未明确，保留该文件，继续验证可识别章节"); continue;
            }
            if(filename.getSeason()!=null&&release.getSeason()!=null&&!filename.getSeason().equals(release.getSeason())) throw new IOException("FILE_SEASON_IDENTITY_CONFLICT");
            if(!filename.getEpisodes().isEmpty()&&!release.getEpisodes().containsAll(filename.getEpisodes())) throw new IOException("FILE_CHAPTER_IDENTITY_CONFLICT");
            if(videos.size()==1&&!filename.getEpisodes().isEmpty()&&!filename.getEpisodes().equals(release.getEpisodes())) throw new IOException("DOWNLOAD_COVERAGE_INCOMPLETE");
            if(!"NORMAL".equals(filename.getChapterType())&&!filename.getChapterType().equals(release.getChapterType())) throw new IOException("FILE_CHAPTER_TYPE_CONFLICT");
            Release declared=GsonStatic.fromJson(GsonStatic.toJson(release),Release.class);
            if(videos.size()>1) {
                if(filename.getEpisodes().isEmpty()) throw new IOException("BATCH_FILE_CHAPTER_UNKNOWN");
                declared.setEpisodes(filename.getEpisodes()); declared.setChapterType(filename.getChapterType());
                if(!release.getEpisodes().containsAll(declared.getEpisodes())) continue;
            }
            var verified=verification.verify(video,declared,settings);
            Release actual=verified.attributes();
            verification.supplementSubtitles(actual,video,subtitles);
            JobFile install=new JobFile(); install.setSource(video.toString()); install.setAttributes(actual); install.setSize(verified.size());
            install.setModifiedAt(verified.modifiedAt()); install.setFileKey(verified.fileKey()); install.setSha256(verified.sha256());
            String title=safeName(target.getTitle()); String ext=verified.attributes().getContainer();
            String hash=release.getInfoHash().substring(0,8);
            String template=ConfigUtil.snapshot().getRenameTemplate();
            if(template==null||template.isBlank()) template="${title} S${seasonFormat}E${episodeFormat}";
            Integer identifiedSeason=target.getSeason()==null?declared.getSeason():target.getSeason();
            String season=String.valueOf(identifiedSeason==null?1:identifiedSeason);
            List<String> displayEpisodes=declared.getEpisodes().stream().map(e->SubjectEpisodeResolver.displayChapter(target,Integer.valueOf(season),e)).toList();
            String episode=String.join("-",displayEpisodes);
            String formattedEpisode=String.join("-",displayEpisodes.stream().map(e->{ try { var n=new java.math.BigDecimal(e); return String.format("%02d",n.intValue())+(n.stripTrailingZeros().scale()>0?e.substring(e.indexOf('.')):""); } catch(RuntimeException ignored) { return e; } }).toList());
            String rendered=template.replace("${title}",title).replace("${jpTitle}",Objects.toString(target.getOriginalTitle(),title)).replace("${season}",season).replace("${seasonFormat}",String.format("%02d",Integer.parseInt(season))).replace("${episode}",episode).replace("${episodeFormat}",formattedEpisode).replace("${subgroup}",String.join("&",declared.getGroups())).replace("${resolution}",verified.attributes().getResolution()+"p").replace("${bgmId}",target.getSubjectId()).replace("${itemTitle}",Objects.toString(declared.getTitle(),title)).replaceAll("\\$\\{[^}]+}","");
            String filenameOut=safeName(rendered)+" ["+verified.attributes().getResolution()+"p-"+hash+"]."+ext;
            if("MOVIE".equals(declared.getChapterType())) filenameOut=title+" ["+verified.attributes().getResolution()+"p-"+hash+"]."+ext;
            Path directory=target.getOutputDirectory()==null?Path.of(target.getOutputRoot()).resolve(title):Path.of(target.getOutputDirectory());
            Path destination=directory.resolve(filenameOut);
            install.setDestination(destination.toString()); files.add(install); coverage.addAll(declared.getEpisodes());
            for(Path subtitle:subtitles) {
                String videoBase=UpgradePaths.fileName(video).replaceFirst("\\.[^.]+$","");
                if(!UpgradePaths.fileName(subtitle).startsWith(videoBase+".")) continue;
                String suffix=UpgradePaths.fileName(subtitle).substring(videoBase.length());
                JobFile sub=new JobFile(); sub.setKind("SUBTITLE"); sub.setSource(subtitle.toString()); sub.setAttributes(declared);
                sub.setSize(Files.size(subtitle)); sub.setSha256(MediaVerificationService.sha256(subtitle));
                sub.setDestination(destination.resolveSibling(filenameOut.replaceFirst("\\.[^.]+$","")+suffix).toString()); files.add(sub);
            }
        }
        if(!coverage.containsAll(release.getEpisodes())) throw new IOException("DOWNLOAD_COVERAGE_INCOMPLETE");
        return files;
    }
    public static String safeName(String value) {
        String name=Objects.toString(value,"未命名作品").replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]","_").replaceAll("[ .]+$","");
        if(name.isBlank()||Set.of("CON","PRN","AUX","NUL","COM1","LPT1").contains(name.toUpperCase(Locale.ROOT))) name="_"+name;
        return name.substring(0,Math.min(120,name.length()));
    }
}
