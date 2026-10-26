package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.service.TaskCoordinator;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

@Service
public class UpgradeScheduler {
    private final UpgradeRepository repository;
    private final UpgradeCampaignService campaigns;
    private final SubjectCatalogProvider catalog;
    private final EmbyLibraryIndexService emby;
    private final ReleaseProvider releases;
    private final AiAnalysisClient ai;
    private final QualityPolicyEngine engine;
    private final UpgradeDownloadService downloads;
    private final UpgradeCommitService commits;
    private final SpaceReservationService space;
    private final TaskCoordinator coordinator;
    private final SubjectEpisodeResolver resolver;
    private final MetadataEnrichmentService metadata;
    private final UpgradeNotificationService notifications;
    private FileChannel instanceChannel;
    private FileLock instanceLock;
    private volatile boolean ready;
    private int targetOffset;
    private long nextRetention;
    private final Object queueLock=new Object();

    public UpgradeScheduler(UpgradeRepository repository,UpgradeCampaignService campaigns,SubjectCatalogProvider catalog,EmbyLibraryIndexService emby,
                            ReleaseProvider releases,AiAnalysisClient ai,QualityPolicyEngine engine,UpgradeDownloadService downloads,
                            UpgradeCommitService commits,SpaceReservationService space,TaskCoordinator coordinator,SubjectEpisodeResolver resolver,MetadataEnrichmentService metadata,UpgradeNotificationService notifications) {
        this.repository=repository; this.campaigns=campaigns; this.catalog=catalog; this.emby=emby; this.releases=releases;
        this.ai=ai; this.engine=engine; this.downloads=downloads; this.commits=commits; this.space=space; this.coordinator=coordinator;
        this.resolver=resolver;
        this.metadata=metadata; this.notifications=notifications;
    }
    public void acquireInstance() throws IOException {
        Path directory=ConfigUtil.getConfigDir().toPath(); Files.createDirectories(directory);
        instanceChannel=FileChannel.open(directory.resolve("instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
        instanceLock=instanceChannel.tryLock();
        if(instanceLock==null) throw new IOException("已有实例管理同一配置数据库，拒绝同时启动");
    }
    public void recover() {
        // Opening the repository completes schema migrations before any file work.
        for(Job job:allJobs()) {
            job.setLeaseUntil(0);
            if(job.getReservedBytes()>0&&job.getStagingPath()!=null&&job.getState()!=JobState.COMPLETED&&(job.getState()!=JobState.CANCELLED||"CANCEL_STOP_PENDING".equals(job.getReasonCode()))) {
                try { space.reserve("download:"+job.getId(),Path.of(ConfigUtil.snapshot().getUpgrade().getStagingRoot()),job.getReservedBytes(),ConfigUtil.snapshot().getUpgrade().getReserveBytes()); }
                catch(Exception e) { repository.event(job.getId(),"SPACE_RESERVATION_RECOVERY", "空间预约恢复等待检查"); }
            }
            if(Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) {
                try { commits.commit(job,target(job)); }
                catch(Exception e) { fail(job,e); }
            }
            if("UPGRADE".equals(job.getKind())&&job.getState()==JobState.COMPLETED) { notifications.completed(target(job),job); enqueue("METADATA",job.getTargetId()); }
        }
        ready=true;
    }
    @PreDestroy public void close() throws IOException {
        ready=false;
        if(instanceLock!=null) instanceLock.release(); if(instanceChannel!=null) instanceChannel.close();
    }
    public Job enqueue(String kind,String parentId) { synchronized(queueLock) {
        String key=kind+":"+parentId;
        Job existing=repository.identity("upgrade_jobs",key,Job.class).orElse(null);
        if(existing!=null&&existing.getState()!=JobState.COMPLETED&&existing.getState()!=JobState.CANCELLED&&existing.getState()!=JobState.FAILED) return existing;
        Job job=existing==null?new Job():existing;
        if(job.getId()==null) job.setId(UUID.randomUUID().toString());
        job.setIdentityKey(key); job.setKind(kind); job.setState(JobState.QUEUED); job.setRetryAt(0);
        if("CATALOG".equals(kind)) job.setCampaignId(parentId); else job.setTargetId(parentId);
        repository.resetCancellation(job.getId()); commits.saveJob(job); return job;
    }
    }
    private List<Job> allJobs() {
        List<Job> result=new ArrayList<>();
        for(int offset=0;;offset+=200) { List<Job> page=repository.list("upgrade_jobs",null,null,offset,200,Job.class); result.addAll(page); if(page.size()<200) return result; }
    }
    private Target target(Job job) { return repository.get("upgrade_targets",job.getTargetId(),Target.class).orElseThrow(()->new IllegalArgumentException("目标不存在")); }

    @Scheduled(fixedDelay=10000,initialDelay=10000)
    public synchronized void tick() {
        if(!ready||coordinator.state()==TaskCoordinator.State.MAINTENANCE||coordinator.state()==TaskCoordinator.State.FAILED) return;
        try {
            if(System.currentTimeMillis()>=nextRetention) { repository.pruneExpiredEvents(System.currentTimeMillis()); nextRetention=System.currentTimeMillis()+86400000L; }
            boolean automatic=ConfigUtil.snapshot().getUpgradeMode()==UpgradeMode.AUTOMATIC;
            int processed=0;
            for(Job job:allJobs()) {
                if(processed>=12||Thread.currentThread().isInterrupted()) break;
                if(job.getRetryAt()>System.currentTimeMillis()) continue;
                if(repository.cancelRequested(job.getId())&&!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING,JobState.COMPLETED).contains(job.getState())) {
                    if(job.getRemoteTaskId()!=null||job.getReservedBytes()>0||job.getState()!=JobState.CANCELLED) { cancelTransfer(job); processed++; }
                    continue;
                }
                if(Set.of(JobState.COMPLETED,JobState.CANCELLED,JobState.FAILED,JobState.NOT_BETTER,JobState.NEEDS_REVIEW).contains(job.getState())) continue;
                if("UPGRADE".equals(job.getKind())&&!automatic&&!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) {
                    if(job.getState()!=JobState.PAUSED) { job.setState(JobState.PAUSED); job.setReasonCode("AUTOMATIC_MODE_DISABLED"); job.setMessage("自动模式已关闭，保留下载和候选"); commits.saveJob(job); }
                    continue;
                }
                job.setLeaseUntil(System.currentTimeMillis()+300000); commits.saveJob(job); processed++;
                try { process(job); } catch(Exception e) { fail(job,e); }
                finally { job.setLeaseUntil(0); commits.saveJob(job); }
            }
            if(!automatic) return;
            for(String library:ConfigUtil.snapshot().getUpgrade().getLibraryIds()) {
                Cursor last=repository.get("upgrade_scan_cursors","emby:"+library,Cursor.class).orElse(null);
                if(last==null||!last.isComplete()||last.getLastSuccess()<System.currentTimeMillis()-1800000L) enqueue("EMBY",library);
            }
            for(Campaign campaign:repository.all("upgrade_campaigns",null,"ENABLED",Campaign.class)) {
                Cursor cursor=repository.get("upgrade_scan_cursors","catalog:"+campaign.getId(),Cursor.class).orElse(null);
                if(cursor==null||!cursor.isComplete()||cursor.getLastSuccess()<System.currentTimeMillis()-86_400_000L) {
                    if(cursor!=null&&cursor.isComplete()) {
                        cursor=new Cursor(); cursor.setId("catalog:"+campaign.getId()); cursor.setScope(campaign.getId()); repository.save("upgrade_scan_cursors",cursor.getId(),campaign.getId(),null,"PENDING",1,cursor);
                        if("MIKAN".equals(campaign.getCatalogSource())) { Cursor fresh=new Cursor(); fresh.setId("mikan-catalog:"+campaign.getId()); fresh.setScope(campaign.getId()); repository.save("upgrade_scan_cursors",fresh.getId(),campaign.getId(),null,"PENDING",1,fresh); }
                    }
                    enqueue("CATALOG",campaign.getId());
                }
            }
            List<Target> targets=repository.list("upgrade_targets",null,null,targetOffset,20,Target.class);
            targetOffset=targets.size()<20?0:targetOffset+20;
            for(Target target:targets) {
                if(!campaigns.eligible(target)||target.getNextScan()>System.currentTimeMillis()) continue;
                if(!UpgradeCampaignService.monitorOnly(target.getLibraryId())&&!emby.fresh(target.getLibraryId())) enqueue("EMBY",target.getLibraryId());
                enqueue("TARGET",target.getId());
                long interval=target.isFinished()||Set.of("剧场版","OVA","OAD","Movie").contains(Objects.toString(target.getType(),""))?21600000:900000;
                target.setNextScan(System.currentTimeMillis()+interval); repository.save("upgrade_targets",target.getId(),target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target);
            }
        } catch(RuntimeException e) { /* Individual source failures are persisted on their jobs. */ }
    }
    void cancelTransfer(Job job) {
        job.setState(JobState.CANCELLED);
        try {
            downloads.stopOwned(job); space.release("download:"+job.getId()); job.setReservedBytes(0);
            job.setReasonCode("CANCELLED_TRANSFER_STOPPED"); job.setMessage("任务已取消，保留文件；独占下载已停止，共享或普通下载保持运行"); job.setRetryAt(0); job.setRetryable(false);
        } catch(Exception e) {
            job.setReasonCode("CANCEL_STOP_PENDING"); job.setMessage("已取消后续安装，下载停止失败，将重试并保留空间预约"); job.setRetryAt(System.currentTimeMillis()+60000); job.setRetryable(true);
            repository.event(job.getId(),"CANCEL_STOP_PENDING",job.getMessage());
        }
        commits.saveJob(job);
    }
    void process(Job job) throws IOException {
        if("CATALOG".equals(job.getKind())) {
            Campaign c=repository.get("upgrade_campaigns",job.getCampaignId(),Campaign.class).orElseThrow();
            if(catalog.scanStep(c)) {
                Cursor cursor=repository.get("upgrade_scan_cursors","catalog:"+c.getId(),Cursor.class).orElseThrow();
                complete(job,cursor.isCoverageIncomplete()?"已处理可访问条目，但上游窗口限制导致目录覆盖未完整":"目录发现完成，可访问条目已纳入监控");
                if(cursor.isCoverageIncomplete()) { job.setReasonCode("CATALOG_COVERAGE_INCOMPLETE"); commits.saveJob(job); }
                for(Target discovered:repository.all("upgrade_targets",c.getLibraryId(),null,Target.class)) if(repository.get("upgrade_target_campaigns",discovered.getId()+":"+c.getId(),com.google.gson.JsonObject.class).isPresent()) enqueue("TARGET",discovered.getId());
            } return;
        }
        if("EMBY".equals(job.getKind())) { if(emby.scanStep(job.getTargetId())) complete(job,"Emby 全量索引完成"); return; }
        Target t=target(job);
        if("METADATA".equals(job.getKind())) { try { metadata.enrich(t,job); complete(job,"元数据补缺与定向刷新完成"); } catch(Exception e) { throw new IOException("METADATA_REFRESH_DEFERRED",e); } return; }
        if("SUBJECT".equals(job.getKind())) { if(resolver.enrichStep(t)) complete(job,"作品详情与章节映射已保存"); return; }
        if("TARGET".equals(job.getKind())) {
            if(!resolver.enrichStep(t)) return;
            if(!UpgradeCampaignService.monitorOnly(t.getLibraryId())) emby.adopt(t);
            releases.rss(t);
            boolean complete;
            try { complete=releases.scanStep(t); }
            catch(UpgradeHttp.SourceFailure error) {
                evaluateTarget(t); throw error;
            }
            evaluateTarget(t);
            if(complete) complete(job,"候选检索与决策完成"); return;
        }
        if(!"UPGRADE".equals(job.getKind())) return;
        if(UpgradeCampaignService.monitorOnly(t.getLibraryId())&&!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) {
            job.setState(JobState.PAUSED); job.setReasonCode("LIBRARY_REQUIRED"); job.setMessage("仅订阅监测：关联媒体库后才能自动下载或替换"); commits.saveJob(job); return;
        }
        if(!campaigns.eligible(t)&&!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) {
            job.setState(JobState.PAUSED); job.setMessage("日期任务暂停、范围缩小或目标已排除"); commits.saveJob(job); return;
        }
        Release r=repository.get("upgrade_releases",job.getReleaseId(),Release.class).orElseThrow();
        var p=campaigns.effectivePolicy(t);
        if(Set.of(JobState.QUEUED,JobState.PAUSED,JobState.RETRY_WAIT,JobState.WAITING_EVIDENCE).contains(job.getState())&&job.getRemoteTaskId()==null) {
            if(!emby.fresh(t.getLibraryId())) throw new IOException("EMBY_INDEX_STALE");
            long active=allJobs().stream().filter(j->j.getState()==JobState.DOWNLOADING).count();
            if(active>=ConfigUtil.snapshot().getUpgrade().getDownloadConcurrency()) return;
            job.setGeneration(ConfigUtil.snapshot().getUpgradeModeRevision()); job.setPolicyId(p.getId()); job.setPolicyRevision(p.getRevision()); job.setTargetRevision(t.getRevision());
            downloads.submit(job,r); job.setState(JobState.DOWNLOADING); job.setReasonCode("DOWNLOAD_SUBMITTED"); job.setMessage("候选已进入独立暂存下载"); commits.saveJob(job); return;
        }
        if(job.getState()==JobState.READY_TO_COMMIT&&(job.getGeneration()!=ConfigUtil.snapshot().getUpgradeModeRevision()||job.getPolicyRevision()!=p.getRevision()||!Objects.equals(job.getPolicyId(),p.getId())||job.getTargetRevision()!=t.getRevision())) job.setState(JobState.VERIFYING);
        if(Set.of(JobState.DOWNLOADING,JobState.RETRY_WAIT,JobState.PAUSED,JobState.VERIFYING).contains(job.getState())&&job.getRemoteTaskId()!=null) {
            var task=downloads.task(job,r).orElseThrow(()->new IOException("REMOTE_TASK_MISSING"));
            if("ARIA2_METADATA".equals(task.getCategory())) { if(System.currentTimeMillis()-job.getLastProgressAt()>ConfigUtil.snapshot().getUpgrade().getNoProgressHours()*3600000L) throw new IOException("DOWNLOAD_NO_PROGRESS"); return; }
            long bytes=task.getCompleted()==null?0:task.getCompleted();
            if(bytes>job.getDownloadedBytes()) { job.setDownloadedBytes(bytes); job.setLastProgressAt(System.currentTimeMillis()); commits.saveJob(job); }
            var settings=ConfigUtil.snapshot().getUpgrade();
            if(!task.finished()) {
                if(System.currentTimeMillis()-job.getCreatedAt()>settings.getDownloadTimeoutHours()*3600000L) throw new IOException("DOWNLOAD_TOTAL_TIMEOUT");
                if(System.currentTimeMillis()-job.getLastProgressAt()>settings.getNoProgressHours()*3600000L) throw new IOException("DOWNLOAD_NO_PROGRESS");
                return;
            }
            space.release("download:"+job.getId()); job.setReservedBytes(0);
            job.setDecisionFingerprint(commits.comparisonFingerprint(t,p,r));
            job.setPolicyId(p.getId()); job.setPolicyRevision(p.getRevision()); job.setTargetRevision(t.getRevision()); job.setReleaseRevision(r.getRevision());
            job.setState(JobState.VERIFYING); commits.saveJob(job);
            List<JobFile> verified=downloads.verify(job,t,r,task);
            List<Version> old=versions(t);
            List<JobFile> selected=new ArrayList<>(); Set<String> replaced=new HashSet<>();
            for(JobFile file:verified) {
                if(!"VIDEO".equals(file.getKind())) continue;
                List<Version> overlapping=old.stream().filter(v->sameChapter(v.getAttributes(),file.getAttributes())&&!Collections.disjoint(v.getAttributes().getEpisodes(),file.getAttributes().getEpisodes())).toList();
                if(overlapping.isEmpty()) { if(engine.evaluate(p,file.getAttributes(),null).verdict()==QualityPolicyEngine.Verdict.UPGRADE) selected.add(file); continue; }
                Set<String> improvements=new HashSet<>(); boolean worthInstalling=false;
                for(Version v:overlapping) {
                    Release comparable=GsonStatic.fromJson(GsonStatic.toJson(v.getAttributes()),Release.class);
                    comparable.getEpisodes().retainAll(file.getAttributes().getEpisodes());
                    var decision=engine.evaluate(p,file.getAttributes(),comparable);
                    if(decision.verdict()==QualityPolicyEngine.Verdict.UPGRADE) { worthInstalling=true; if(!v.isProtectedVersion()) improvements.add(v.getId()); }
                }
                if(worthInstalling) { selected.add(file); replaced.addAll(improvements); }
            }
            List<JobFile> subtitleFiles=verified.stream().filter(f->"SUBTITLE".equals(f.getKind()))
                    .filter(f->selected.stream().anyMatch(v->f.getDestination().startsWith(v.getDestination().replaceFirst("\\.[^.]+$","")+"."))).toList();
            selected.addAll(subtitleFiles);
            if(selected.isEmpty()) { job.setState(JobState.NOT_BETTER); job.setReasonCode("NOT_BETTER_AFTER_VERIFICATION"); job.setMessage("实际媒体字段未证明优于当前版本，保留下载，继续选择其他候选；条件改变后重新验证"); job.setRetryable(true); commits.saveJob(job); return; }
            job.setGeneration(ConfigUtil.snapshot().getUpgradeModeRevision()); job.setPolicyRevision(p.getRevision()); job.setPolicyId(p.getId()); job.setTargetRevision(t.getRevision());
            commits.prepare(job,t,selected,old.stream().filter(v->replaced.contains(v.getId())).toList());
            space.release("download:"+job.getId());
        }
        if(Set.of(JobState.READY_TO_COMMIT,JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) {
            commits.commit(job,t);
            if(job.getState()==JobState.COMPLETED) { notifications.completed(t,job); enqueue("METADATA",t.getId()); }
        }
    }
    private List<Version> versions(Target t) { return repository.all("upgrade_versions",t.getId(),"ACTIVE",Version.class).stream().filter(v->"VIDEO".equals(v.getKind())).toList(); }
    public synchronized void evaluateTarget(Target t) {
        evaluateTarget(t,true);
    }
    public synchronized void reanalyzeTarget(Target t) {
        evaluateTarget(t,false);
    }
    private void evaluateTarget(Target t,boolean allowExternalEvidence) {
        releases.refreshStoredEvidence(t);
        var p=campaigns.effectivePolicy(t);
        // The transfer is isolated from the library. A missing container can be
        // learned from downloaded metadata; actual installation always uses the
        // complete policy and ffprobe result again.
        var discoveryPolicy=GsonStatic.fromJson(GsonStatic.toJson(p),QualityPolicyEngine.Policy.class);
        discoveryPolicy.setContainers(Set.of());
        List<Release> candidates=new ArrayList<>();
        for(int offset=0;;offset+=200) { List<Release> page=repository.list("upgrade_releases",t.getId(),null,offset,200,Release.class); candidates.addAll(page); if(page.size()<200) break; }
        SubjectEpisodeResolver.learnSeasonStart(t,candidates);
        int details=0;
        for(Release candidate:candidates) if(allowExternalEvidence&&details<4&&"ANIMEGARDEN".equals(candidate.getSource())&&candidate.getDetailAt()<System.currentTimeMillis()-86400000L
                &&(candidate.getEpisodes().isEmpty()||candidate.getLanguages().isEmpty()||candidate.getGroups().isEmpty())) {
            details++; try { releases.enrich(candidate); } catch(RuntimeException error) { repository.event(candidate.getId(),"RELEASE_DETAIL_DEFERRED","发布详情暂不可用，保留已知证据并继续其他候选"); }
        }
        String aiFailure=null;
        try { if(allowExternalEvidence) ai.analyze(candidates); } catch(RuntimeException e) { aiFailure=Objects.toString(e.getMessage(),"AI_UNAVAILABLE"); repository.event(t.getId(),"AI_ANALYSIS_DEFERRED",aiFailure); }
        List<Version> current=versions(t);
        candidates.sort(Comparator.comparing(r->Objects.toString(r.getInfoHash(),r.getId())));
        Map<String,Release> best=new LinkedHashMap<>();
        for(Release r:candidates) {
            if("MERGED".equals(r.getState())) continue;
            SubjectEpisodeResolver.normalizeChapters(t,r);
            if(r.getRetryAt()>System.currentTimeMillis()) continue;
            if(t.getSeason()!=null&&r.getSeason()==null) r.setSeason(t.getSeason());
            if(SubjectEpisodeResolver.isMovie(t)) { r.getEpisodes().clear(); r.getEpisodes().add("MOVIE"); r.setChapterType("MOVIE"); }
            Job assessed=repository.identity("upgrade_jobs",downloadKey(t,r),Job.class).orElse(null);
            if(assessed!=null&&(assessed.getState()==JobState.CANCELLED||Set.of(JobState.NOT_BETTER,JobState.NEEDS_REVIEW).contains(assessed.getState())
                    &&Objects.equals(assessed.getDecisionFingerprint(),commits.comparisonFingerprint(t,p,r)))) {
                if(assessed.getState()!=JobState.CANCELLED) { r.setState(assessed.getState().name()); r.setReason(assessed.getMessage()); repository.save("upgrade_releases",r.getId(),r.getTargetId(),r.getTargetId()+":"+(r.getInfoHash()==null?r.getSource()+":"+r.getSourceId():r.getInfoHash()),r.getState(),r.getRevision(),r); }
                continue;
            }
            boolean worth=false;
            for(String episode:r.getEpisodes()) {
                List<Version> baseline=current.stream().filter(v->sameChapter(v.getAttributes(),r)&&v.getAttributes().getEpisodes().contains(episode)).toList();
                var decision=engine.evaluate(discoveryPolicy,r,null);
                if(!baseline.isEmpty()) {
                    decision=baseline.stream().map(v->engine.evaluate(discoveryPolicy,episodeView(r,episode),episodeView(v.getAttributes(),episode)))
                            .filter(d->d.verdict()==QualityPolicyEngine.Verdict.UPGRADE).findFirst()
                            .orElseGet(()->engine.evaluate(discoveryPolicy,episodeView(r,episode),episodeView(baseline.get(0).getAttributes(),episode)));
                }
                if(baseline.isEmpty()||baseline.stream().anyMatch(v->engine.evaluate(discoveryPolicy,episodeView(r,episode),episodeView(v.getAttributes(),episode)).verdict()==QualityPolicyEngine.Verdict.UPGRADE)) {
                    if(decision.verdict()==QualityPolicyEngine.Verdict.UPGRADE) {
                        String chapterKey=r.getSubjectId()+":"+r.getSeason()+":"+r.getChapterType()+":"+r.getContentEdition()+":"+episode;
                        Release other=best.get(chapterKey);
                        if(other==null||engine.evaluate(discoveryPolicy,episodeView(r,episode),episodeView(other,episode)).verdict()==QualityPolicyEngine.Verdict.UPGRADE) best.put(chapterKey,r);
                        worth=true;
                    }
                }
                r.setState(decision.verdict().name()); r.setReason(String.join("；",decision.explanation()));
            }
            if(r.getEvidence().stream().anyMatch(e->"SOURCE_IDENTITY_REJECTED".equals(e.getSource()))) { r.setState("IDENTITY_CONFLICT"); r.setReason("来源身份不符，保留记录并继续其他发布的检索"); }
            else if(r.getEpisodes().isEmpty()) { r.setState("WAITING_EVIDENCE"); r.setReason("需要解析明确章节覆盖，已保留候选供补证"); }
            if(aiFailure!=null&&"WAITING_EVIDENCE".equals(r.getState())) { r.setState("WAITING_AI"); r.setReason(r.getReason()+"；模型补证待恢复："+aiFailure); }
            repository.save("upgrade_releases",r.getId(),r.getTargetId(),r.getTargetId()+":"+(r.getInfoHash()==null?r.getSource()+":"+r.getSourceId():r.getInfoHash()),r.getState(),r.getRevision(),r);
        }
        boolean identityReview="NEEDS_REVIEW".equals(t.getState());
        if(!identityReview) t.setState(candidates.isEmpty()?(t.getPremiereDate()!=null&&t.getPremiereDate().compareTo(java.time.LocalDate.now(java.time.ZoneId.of(ConfigUtil.snapshot().getUpgrade().getTimezone())).toString())>0?"WAITING_PREMIERE":"NO_RESOURCE"):"MONITORING");
        repository.save("upgrade_targets",t.getId(),t.getLibraryId(),t.getSubjectId()+":"+t.getLibraryId(),t.getState(),t.getRevision(),t);
        if(identityReview||UpgradeCampaignService.monitorOnly(t.getLibraryId())||ConfigUtil.snapshot().getUpgradeMode()!=UpgradeMode.AUTOMATIC||!campaigns.eligible(t)||!emby.fresh(t.getLibraryId())) return;
        for(Release r:new LinkedHashSet<>(best.values())) {
            if(r.getInfoHash()==null) continue;
            String key=downloadKey(t,r);
            Job previous=repository.identity("upgrade_jobs",key,Job.class).orElse(null);
            if(previous!=null) {
                if(Set.of(JobState.NOT_BETTER,JobState.NEEDS_REVIEW).contains(previous.getState())&&!Objects.equals(previous.getDecisionFingerprint(),commits.comparisonFingerprint(t,p,r))) {
                    previous.setState(previous.getRemoteTaskId()==null?JobState.QUEUED:JobState.VERIFYING); previous.setRetryAt(0); previous.setPolicyId(p.getId()); previous.setPolicyRevision(p.getRevision()); previous.setTargetRevision(t.getRevision()); previous.setReleaseRevision(r.getRevision()); commits.saveJob(previous);
                }
                if(previous.getState()==JobState.FAILED&&previous.getRetryAt()<=System.currentTimeMillis()) { previous.setState(previous.getRemoteTaskId()==null?JobState.QUEUED:JobState.DOWNLOADING); previous.setLastProgressAt(System.currentTimeMillis()); previous.setCreatedAt(System.currentTimeMillis()); commits.saveJob(previous); }
                continue;
            }
            Job job=new Job(); job.setId(UUID.randomUUID().toString()); job.setIdentityKey(key); job.setTargetId(t.getId()); job.setReleaseId(r.getId());
            job.setPolicyId(p.getId()); job.setPolicyRevision(p.getRevision()); job.setTargetRevision(t.getRevision()); job.setReleaseRevision(r.getRevision()); job.setState(JobState.QUEUED); commits.saveJob(job);
        }
    }
    private static String downloadKey(Target target,Release release) { return "download:"+target.getId()+":"+release.getInfoHash()+":"+target.getOutputRoot(); }
    private static Release episodeView(Release release,String episode) { Release copy=GsonStatic.fromJson(GsonStatic.toJson(release),Release.class); copy.setEpisodes(new TreeSet<>(Set.of(episode))); return copy; }
    private static boolean sameChapter(Release first,Release second) { return Objects.equals(first.getSubjectId(),second.getSubjectId())&&Objects.equals(first.getChapterType(),second.getChapterType())&&Objects.equals(first.getSeason(),second.getSeason())&&Objects.equals(first.getContentEdition(),second.getContentEdition()); }
    private void complete(Job job,String message) { job.setState(JobState.COMPLETED); job.setReasonCode("SCAN_COMPLETE"); job.setMessage(message); job.setRetryable(false); commits.saveJob(job); }
    private void fail(Job job,Exception error) {
        if(job.getState()==JobState.CLEANUP_PENDING) { job.setRetryAt(System.currentTimeMillis()+60000); commits.saveJob(job); return; }
        String code=Objects.toString(error.getMessage(),"UPGRADE_OPERATION_FAILED");
        if("BASELINE_CHANGED".equals(code)) { job.setState(JobState.VERIFYING); job.setRetryAt(0); job.setRetryable(true); commits.saveJob(job); repository.event(job.getId(),code,job.getMessage()); return; }
        if(Set.of("DOWNLOAD_NO_PROGRESS","DOWNLOAD_TOTAL_TIMEOUT","NO_SUPPORTED_VIDEO_FILES","MEDIA_PROCESS_FAILED_1","MEDIA_CORRUPT","UNSUPPORTED_RESOURCE_FORMAT").contains(code)) {
            try { downloads.stopOwned(job); }
            catch(IOException stopError) { job.setReasonCode("OWNED_TRANSFER_STOP_PENDING"); job.setMessage("候选不可用，但下载任务停止失败，保留空间预约并等待重试"); job.setState(JobState.RETRY_WAIT); job.setRetryAt(System.currentTimeMillis()+60000); commits.saveJob(job); return; }
            job.setState(JobState.FAILED); job.setReasonCode(code); job.setMessage("候选不可用，24 小时后可重新探测；继续选择其他资源"); job.setRetryable(true); job.setRetryAt(System.currentTimeMillis()+86400000L); commits.saveJob(job);
            repository.get("upgrade_releases",job.getReleaseId(),Release.class).ifPresent(r->{ r.setRetryAt(job.getRetryAt()); r.setState("UNSUPPORTED_RESOURCE_FORMAT".equals(code)?"UNSUPPORTED":"UNAVAILABLE"); r.setReason(job.getMessage()); releases.accept(r); });
            space.release("download:"+job.getId()); job.setReservedBytes(0); commits.saveJob(job); enqueue("TARGET",job.getTargetId()); return;
        }
        job.setReasonCode(code); job.setMessage("操作暂停："+code); job.setAttempts(job.getAttempts()+1); job.setRetryable(true);
        if(job.getState()==JobState.INSTALLING) { /* Retain the immutable manifest for recovery. */ }
        else if(!code.endsWith("_BUSY")&&(code.contains("IDENTITY")||code.contains("CHAPTER")||code.contains("COVERAGE")||code.contains("CHANGED")||code.contains("CONFLICT"))) job.setState(JobState.NEEDS_REVIEW);
        else job.setState(JobState.RETRY_WAIT);
        long delay=Math.min(3600000,10000L*(1L<<Math.min(job.getAttempts(),8)))+java.util.concurrent.ThreadLocalRandom.current().nextInt(5000);
        if(error instanceof UpgradeHttp.SourceFailure failure) delay=Math.max(delay,failure.retryAfterMillis());
        job.setRetryAt(System.currentTimeMillis()+delay);
        commits.saveJob(job); repository.event(job.getId(),code,job.getMessage());
    }
}
