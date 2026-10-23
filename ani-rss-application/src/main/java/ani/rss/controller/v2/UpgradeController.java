package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.upgrade.*;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/v2/upgrade")
public class UpgradeController {
    private final UpgradeRepository repository;
    private final UpgradeModeService modes;
    private final UpgradeCampaignService campaigns;
    private final QualityPolicyEngine engine;
    private final UpgradeScheduler scheduler;
    private final UpgradeCommitService commits;
    private final EmbyLibraryIndexService emby;
    private final AiAnalysisClient ai;
    private final MediaVerificationService media;
    private volatile long dependencyAt;
    private volatile Map<String,Boolean> dependencies=Map.of();
    public UpgradeController(UpgradeRepository repository,UpgradeModeService modes,UpgradeCampaignService campaigns,QualityPolicyEngine engine,
                             UpgradeScheduler scheduler,UpgradeCommitService commits,EmbyLibraryIndexService emby,AiAnalysisClient ai,MediaVerificationService media) {
        this.repository=repository; this.modes=modes; this.campaigns=campaigns; this.engine=engine; this.scheduler=scheduler; this.commits=commits; this.emby=emby; this.ai=ai; this.media=media;
    }
    @Auth @GetMapping("/status") public Map<String,Object> status() {
        var settings=ConfigUtil.snapshot().getUpgrade();
        if(dependencyAt<System.currentTimeMillis()-30000) {
            dependencies=Map.of("ffprobe",media.available(settings.getFfprobe()),"ffmpeg",media.available(settings.getFfmpeg())); dependencyAt=System.currentTimeMillis();
        }
        return Map.ofEntries(Map.entry("mode",modes.mode()),Map.entry("jobs",repository.counts("upgrade_jobs")),Map.entry("targets",repository.counts("upgrade_targets")),
                Map.entry("candidates",repository.counts("upgrade_releases")),Map.entry("dependencies",dependencies),Map.entry("aiEnabled",settings.isAiEnabled()),
                Map.entry("commitInProgress",UpgradeCommitService.committing()||UpgradeFileCoordinator.recoveryPending()),Map.entry("scanCursors",repository.list("upgrade_scan_cursors",null,null,0,200,Cursor.class)),
                Map.entry("aiUsage",repository.get("upgrade_ai_cache","usage:"+java.time.LocalDate.now(java.time.ZoneId.of(settings.getTimezone())),com.google.gson.JsonObject.class).orElseGet(com.google.gson.JsonObject::new)),
                Map.entry("aiTested",Objects.equals(DeepSeekAnalysisClient.fingerprint(settings),settings.getAiTestFingerprint())),Map.entry("coverageMetrics",repository.coverageMetrics()));
    }
    public record ModeRequest(UpgradeMode mode,long expectedRevision) {}
    @Auth @PutMapping("/mode") public UpgradeModeService.ModeView mode(@RequestBody ModeRequest request) { return modes.switchMode(request.mode(),request.expectedRevision()); }
    @Auth @GetMapping("/policies") public List<QualityPolicyEngine.Policy> policies() { return repository.list("upgrade_policies",null,"ACTIVE",0,200,QualityPolicyEngine.Policy.class); }
    @Auth @PostMapping("/policies") public QualityPolicyEngine.Policy createPolicy(@RequestBody QualityPolicyEngine.Policy p) { return campaigns.savePolicy(null,p); }
    @Auth @PutMapping("/policies/{id}") public QualityPolicyEngine.Policy updatePolicy(@PathVariable String id,@RequestBody QualityPolicyEngine.Policy p) { return campaigns.savePolicy(id,p); }
    @Auth @DeleteMapping("/policies/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void deletePolicy(@PathVariable String id,@RequestParam long expectedRevision) { campaigns.deletePolicy(id,expectedRevision); }
    public record Preview(Release candidate,Release current) {}
    public record DraftPreview(QualityPolicyEngine.Policy policy,Release candidate,Release current) {}
    @Auth @PostMapping("/evaluate") public QualityPolicyEngine.Decision draft(@RequestBody DraftPreview preview) { return engine.evaluate(preview.policy(),preview.candidate(),preview.current()); }
    @Auth @GetMapping("/versions") public Page<Version> versions(@RequestParam String targetId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) { return page("upgrade_versions",targetId,null,offset,limit,Version.class); }
    public record Protect(boolean protectedVersion) {}
    @Auth @PutMapping("/versions/{id}/protection") public Version protection(@PathVariable String id,@RequestBody Protect request) {
        try(var lease=UpgradeFileCoordinator.lease()) { Version version=repository.get("upgrade_versions",id,Version.class).orElseThrow(); version.setProtectedVersion(request.protectedVersion()); repository.save("upgrade_versions",id,version.getTargetId(),null,version.isActive()?"ACTIVE":"RETIRED",1,version); repository.event(id,"VERSION_PROTECTION","更新版本保护状态"); return version; }
    }
    @Auth @PostMapping("/emby/import-notification") public Object importEmby() {
        var config=ConfigUtil.snapshot();
        var existing=config.getNotificationConfigList().stream().filter(n->n.getNotificationType()==ani.rss.enums.NotificationTypeEnum.EMBY_REFRESH&&n.getEmbyHost()!=null&&!n.getEmbyHost().isBlank()).findFirst().orElseThrow(()->new IllegalArgumentException("尚无可导入的 Emby 通知连接"));
        config.getUpgrade().setEmbyUrl(existing.getEmbyHost()); config.getUpgrade().setEmbyApiKey(existing.getEmbyApiKey()); config.getUpgrade().setLibraryIds(new ArrayList<>(existing.getEmbyRefreshViewIds())); ConfigUtil.syncExpected(config,config.getUpgradeModeRevision()); return Map.of("imported",true);
    }
    @Auth @PostMapping("/policies/{id}/evaluate") public QualityPolicyEngine.Decision evaluate(@PathVariable String id,@RequestBody Preview preview) {
        return engine.evaluate(repository.get("upgrade_policies",id,QualityPolicyEngine.Policy.class).orElseThrow(),preview.candidate(),preview.current());
    }
    @Auth @GetMapping("/campaigns") public List<Campaign> campaigns() { return repository.list("upgrade_campaigns",null,null,0,200,Campaign.class); }
    @Auth @GetMapping("/mikan/seasons") public Object mikanSeasons() { return campaigns.mikanSeasons(); }
    @Auth @GetMapping("/mikan/preview") public Object mikanPreview(@RequestParam int year,@RequestParam String season) { return campaigns.mikanPreview(year,season); }
    @Auth @PostMapping("/campaigns") public Campaign createCampaign(@RequestBody Campaign c) { Campaign saved=campaigns.saveCampaign(null,c); if(saved.isEnabled()) scheduler.enqueue("CATALOG",saved.getId()); return saved; }
    @Auth @PutMapping("/campaigns/{id}") public Campaign updateCampaign(@PathVariable String id,@RequestBody Campaign c) { Campaign saved=campaigns.saveCampaign(id,c); if(saved.isEnabled()) scheduler.enqueue("CATALOG",saved.getId()); return saved; }
    @Auth @PostMapping("/campaigns/{id}/scan") @ResponseStatus(HttpStatus.ACCEPTED) public Job scan(@PathVariable String id) {
        Campaign c=repository.get("upgrade_campaigns",id,Campaign.class).orElseThrow();
        Cursor cursor=new Cursor(); cursor.setId("catalog:"+id); cursor.setScope(id); repository.save("upgrade_scan_cursors",cursor.getId(),id,null,"PENDING",1,cursor);
        if("MIKAN".equals(c.getCatalogSource())) { Cursor mikanCursor=new Cursor(); mikanCursor.setId("mikan-catalog:"+id); mikanCursor.setScope(id); repository.save("upgrade_scan_cursors",mikanCursor.getId(),id,null,"PENDING",1,mikanCursor); }
        return scheduler.enqueue("CATALOG",c.getId());
    }
    public record Page<T>(List<T> items,int offset,int limit,boolean hasMore) {}
    private <T> Page<T> page(String table,String parent,String state,int offset,int limit,Class<T> type) {
        List<T> rows=repository.list(table,parent,state,offset,limit,type); return new Page<>(rows,offset,limit,rows.size()==limit);
    }
    @Auth @GetMapping("/targets") public Page<Target> targets(@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        Page<Target> result=page("upgrade_targets",null,null,offset,limit,Target.class); campaigns.refreshCampaignSelection(result.items()); return result;
    }
    public record TargetOverride(String policyId,boolean excluded,String embySeriesId,Integer season,Map<String,String> episodeMapping,List<String> rssUrls,String libraryId) {}
    @Auth @PutMapping("/targets/{id}/override") public Target override(@PathVariable String id,@RequestBody TargetOverride request) {
        Target target=repository.get("upgrade_targets",id,Target.class).orElseThrow();
        if(request.libraryId()!=null&&!request.libraryId().equals(target.getLibraryId())) {
            if(!"UNASSIGNED".equals(target.getLibraryId())) throw new IllegalArgumentException("已关联的目标不能直接转库；请在对应媒体库建立独立目标");
            if(!ConfigUtil.snapshot().getUpgrade().getLibraryIds().contains(request.libraryId())) throw new IllegalArgumentException("请选择已配置媒体库");
            Target duplicate=repository.identity("upgrade_targets",target.getSubjectId()+":"+request.libraryId(),Target.class).orElse(null);
            if(duplicate!=null&&!duplicate.getId().equals(id)) throw new ani.rss.exception.ApiProblemException(HttpStatus.CONFLICT,"UPGRADE_TARGET_LIBRARY_CONFLICT","该作品已有对应媒体库档案，请使用已有目标",null,Map.of("targetId",duplicate.getId()));
            target.setLibraryId(request.libraryId()); target.setOutputRoot(SubjectEpisodeResolver.isMovie(target)?ConfigUtil.snapshot().getUpgrade().getMovieRoot():ConfigUtil.snapshot().getUpgrade().getTvRoot());
            target.setState("WAITING_PREMIERE_EVIDENCE");
        }
        if(request.policyId()!=null&&!request.policyId().isBlank()) repository.get("upgrade_policies",request.policyId(),QualityPolicyEngine.Policy.class).orElseThrow();
        target.setPolicyOverride(request.policyId()); target.setExcluded(request.excluded()); target.setEmbySeriesId(request.embySeriesId());
        if(request.season()!=null&&(request.season()<0||request.season()>1000)) throw new IllegalArgumentException("季编号无效");
        target.setSeason(request.season());
        if(request.episodeMapping()!=null) { if(request.episodeMapping().size()>1000) throw new IllegalArgumentException("章节映射过多"); target.setEpisodeMapping(request.episodeMapping()); }
        if(request.rssUrls()!=null) { if(request.rssUrls().size()>20) throw new IllegalArgumentException("RSS 源过多"); request.rssUrls().forEach(UpgradeHttp::resourceUrl); target.setRssUrls(request.rssUrls()); }
        target.setRevision(target.getRevision()+1);
        repository.save("upgrade_targets",id,target.getLibraryId(),target.getSubjectId()+":"+target.getLibraryId(),target.getState(),target.getRevision(),target);
        repository.event(id,"TARGET_OVERRIDE","更新作品映射、规则或持久排除状态");
        if(!target.isExcluded()&&!"UNASSIGNED".equals(target.getLibraryId())) scheduler.enqueue("TARGET",id); return target;
    }
    @Auth @GetMapping("/candidates") public Page<Release> candidates(@RequestParam(required=false) String targetId,@RequestParam(required=false) String state,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return page("upgrade_releases",targetId,state,offset,limit,Release.class);
    }
    @Auth @GetMapping("/preview") public Map<String,Object> preview(@RequestParam(required=false) String targetId,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        List<Release> releases=repository.list("upgrade_releases",targetId,null,offset,limit,Release.class);
        List<Map<String,Object>> decisions=new ArrayList<>(); Map<String,Long> reasons=new TreeMap<>();
        for(Release release:releases) {
            if("MERGED".equals(release.getState())) continue;
            Target target=repository.get("upgrade_targets",release.getTargetId(),Target.class).orElse(null); if(target==null) continue;
            var policy=campaigns.effectivePolicy(target);
            List<Version> baselines=repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).stream().filter(v->"VIDEO".equals(v.getKind())).toList();
            List<Version> overlaps=baselines.stream().filter(v->v.getAttributes()!=null&&Objects.equals(v.getAttributes().getChapterType(),release.getChapterType())&&!Collections.disjoint(v.getAttributes().getEpisodes(),release.getEpisodes())).toList();
            if(overlaps.isEmpty()) {
                var decision=engine.evaluate(policy,release,null); reasons.merge(decision.reasonCode(),1L,Long::sum);
                decisions.add(previewRow(target,release,policy,null,decision));
            } else for(Version version:overlaps) {
                var decision=engine.evaluate(policy,release,version.getAttributes()); reasons.merge(decision.reasonCode(),1L,Long::sum);
                decisions.add(previewRow(target,release,policy,version,decision));
            }
        }
        return Map.of("generatedAt",System.currentTimeMillis(),"readOnly",true,"offset",offset,"limit",limit,"hasMore",releases.size()==limit,"decisions",decisions,"reasonDistribution",reasons);
    }
    private Map<String,Object> previewRow(Target target,Release release,QualityPolicyEngine.Policy policy,Version current,QualityPolicyEngine.Decision decision) {
        return Map.of("targetId",target.getId(),"subjectId",target.getSubjectId(),"title",target.getTitle(),"candidate",release,"currentVersion",current==null?Map.of():current,
                "policyId",Objects.toString(policy.getId(),"default"),"policyRevision",policy.getRevision(),"decision",decision,"indexFresh",emby.fresh(target.getLibraryId()),"protectedVersion",current!=null&&current.isProtectedVersion());
    }
    public record CandidateCorrection(String subjectId,Set<String> episodes,Integer season,Set<String> groups,Set<String> languages,
            String subtitleLayout,String subtitlePackaging,Integer resolution,String chapterType,String contentEdition) {}
    @Auth @PostMapping("/candidates/{id}/resolve") public Release resolve(@PathVariable String id,@RequestBody CandidateCorrection correction) {
        Release r=repository.get("upgrade_releases",id,Release.class).orElseThrow();
        if(correction.subjectId()!=null&&!Objects.equals(correction.subjectId(),r.getSubjectId())) throw new ani.rss.exception.ApiProblemException(HttpStatus.CONFLICT,"UPGRADE_RESOURCE_IDENTITY_CONFLICT","作品身份冲突，不能绕过验证",null,Map.of("candidateId",id));
        if(correction.episodes()==null||correction.episodes().size()>500) throw new IllegalArgumentException("章节数量无效");
        correction.episodes().forEach(e->{ if(!"MOVIE".equals(e)) ReleaseAnalysisService.number(e); });
        if(correction.season()!=null&&(correction.season()<0||correction.season()>1000)||correction.resolution()!=null&&(correction.resolution()<1||correction.resolution()>16384)) throw new IllegalArgumentException("季或分辨率无效");
        if(correction.languages()!=null&&!Set.of("CHS","CHT","JPN","ZHO").containsAll(correction.languages())) throw new IllegalArgumentException("字幕语言无效");
        if(correction.chapterType()!=null&&!Set.of("NORMAL","SPECIAL","EXTRA","OTHER","MOVIE").contains(correction.chapterType())) throw new IllegalArgumentException("章节类型无效");
        if(correction.contentEdition()!=null&&!Set.of("MAIN","DIRECTORS_CUT","RECAP","EXTENDED").contains(correction.contentEdition())) throw new IllegalArgumentException("内容版本无效");
        if(correction.groups()!=null&&(correction.groups().size()>30||correction.groups().stream().anyMatch(g->g==null||g.isBlank()||g.length()>100))) throw new IllegalArgumentException("字幕组无效");
        if(correction.subtitleLayout()!=null&&!Set.of("BILINGUAL","MONOLINGUAL").contains(correction.subtitleLayout())) throw new IllegalArgumentException("字幕布局无效");
        if(correction.subtitlePackaging()!=null&&!Set.of("HARD","SOFT","EXTERNAL").contains(correction.subtitlePackaging())) throw new IllegalArgumentException("字幕封装无效");
        r.setEpisodes(new TreeSet<>(correction.episodes().stream().map(e->"MOVIE".equals(e)?e:ReleaseAnalysisService.number(e)).toList())); r.setSeason(correction.season());
        if(correction.groups()!=null) r.setGroups(new TreeSet<>(correction.groups())); if(correction.languages()!=null) r.setLanguages(new TreeSet<>(correction.languages()));
        r.setSubtitleLayout(correction.subtitleLayout()); r.setSubtitlePackaging(correction.subtitlePackaging()); r.setResolution(correction.resolution());
        if(correction.chapterType()!=null) r.setChapterType(correction.chapterType()); if(correction.contentEdition()!=null) r.setContentEdition(correction.contentEdition());
        r.setState("ANALYZING"); r.setRevision(r.getRevision()+1); r.getEvidence().add(new Evidence("USER_CORRECTION","fields","人工修正后仍需实际文件校验"));
        repository.save("upgrade_releases",id,r.getTargetId(),r.getTargetId()+":"+(r.getInfoHash()==null?r.getSource()+":"+r.getSourceId():r.getInfoHash()),r.getState(),r.getRevision(),r);
        scheduler.enqueue("TARGET",r.getTargetId()); return r;
    }
    @Auth @GetMapping("/jobs") public Page<Job> jobs(@RequestParam(required=false) String targetId,@RequestParam(required=false) String state,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit) {
        return page("upgrade_jobs",targetId,state,offset,limit,Job.class);
    }
    @Auth @GetMapping("/jobs/{id}") public Map<String,Object> job(@PathVariable String id) {
        return Map.of("job",repository.get("upgrade_jobs",id,Job.class).orElseThrow(),"files",repository.all("upgrade_job_files",id,null,JobFile.class),"events",repository.list("upgrade_events",id,null,0,200,Map.class),"commitLocks",new ChapterCommitLockService().list(id));
    }
    @Auth @PostMapping("/jobs/{id}/retry") public Job retry(@PathVariable String id) {
        Job job=repository.get("upgrade_jobs",id,Job.class).orElseThrow();
        if(job.getState()==JobState.COMPLETED) return job;
        if("CANCEL_STOP_PENDING".equals(job.getReasonCode())) { job.setRetryAt(0); commits.saveJob(job); return job; }
        if(!job.isRetryable()&&job.getState()!=JobState.PAUSED&&job.getState()!=JobState.NEEDS_REVIEW) throw new IllegalArgumentException("当前阶段不可重试");
        if(!Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING).contains(job.getState())) job.setState(job.getRemoteTaskId()==null?JobState.QUEUED:JobState.DOWNLOADING);
        repository.resetCancellation(id);
        job.setRetryAt(0); commits.saveJob(job); return job;
    }
    @Auth @PostMapping("/jobs/{id}/cancel") public Job cancel(@PathVariable String id) {
        try(var lease=UpgradeFileCoordinator.lease()) {
        Job job=repository.get("upgrade_jobs",id,Job.class).orElseThrow();
        if(Set.of(JobState.INSTALLING,JobState.INSTALLED,JobState.CLEANING_OLD,JobState.CLEANUP_PENDING,JobState.COMPLETED).contains(job.getState()))
            throw new IllegalArgumentException("文件提交已开始，需先收敛当前提交");
        repository.requestCancellation(id); job.setState(JobState.CANCELLED); job.setMessage("已取消，保留已下载文件"); commits.saveJob(job); return job;
        }
    }
    @Auth @PostMapping("/emby/test") public Map<String,Object> embyTest() { return emby.test(); }
    public record SyncRequest(String libraryId) {}
    @Auth @PostMapping("/emby/sync") @ResponseStatus(HttpStatus.ACCEPTED) public Job sync(@RequestBody SyncRequest request) { return scheduler.enqueue("EMBY",request.libraryId()); }
    @Auth @PostMapping("/ai/test") public Object aiTest() { return ai.test(); }
    @Auth @GetMapping("/ai/models") public Object models() { return ai.models(); }
}
