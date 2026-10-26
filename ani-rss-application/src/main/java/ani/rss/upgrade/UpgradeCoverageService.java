package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.*;
import java.util.*;

/** Coverage is measured against chapter facts; a downloaded sample is not full acceptance. */
public final class UpgradeCoverageService {
    private UpgradeCoverageService() {}
    public static Map<String,Object> assess(UpgradeRepository repository,Target target,QualityPolicyEngine.Policy policy) {
        var discovery=UpgradeModels.snapshot(policy,QualityPolicyEngine.Policy.class); discovery.setContainers(Set.of());
        var engine=new QualityPolicyEngine(); Set<String> expected=new TreeSet<>(),eligible=new TreeSet<>(),installed=new TreeSet<>();
        String today=java.time.LocalDate.now(java.time.ZoneId.of(ani.rss.util.other.ConfigUtil.snapshot().getUpgrade().getTimezone())).toString();
        if(SubjectEpisodeResolver.isMovie(target)) expected.add("MOVIE:MOVIE");
        else for(String key:target.getChapterTypes().keySet()) if(key.contains(":")) {
            String date=target.getChapterAirDates().get(key);
            if((date==null||date.compareTo(today)<=0)&&!key.startsWith("EXTRA:")&&!key.startsWith("OTHER:")) expected.add(key);
        }
        List<Release> releases=repository.all("upgrade_releases",target.getId(),null,Release.class);
        SubjectEpisodeResolver.learnSeasonStart(target,releases);
        int qualifying=0,identityConflicts=0,evidencePending=0;
        for(Release saved:releases) {
            if("MERGED".equals(saved.getState())) continue;
            Release release=UpgradeModels.snapshot(saved,Release.class); SubjectEpisodeResolver.normalizeChapters(target,release);
            var decision=engine.evaluate(discovery,release,null);
            if(decision.verdict()==QualityPolicyEngine.Verdict.UPGRADE) { qualifying++; release.getEpisodes().forEach(e->eligible.add(release.getChapterType()+":"+e)); }
            if(decision.verdict()==QualityPolicyEngine.Verdict.IDENTITY_CONFLICT) identityConflicts++;
            if(decision.verdict()==QualityPolicyEngine.Verdict.WAITING_EVIDENCE) evidencePending++;
        }
        for(Version version:repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class)) if(version.getAttributes()!=null) {
            Release actual=UpgradeModels.snapshot(version.getAttributes(),Release.class); SubjectEpisodeResolver.normalizeChapters(target,actual);
            actual.getEpisodes().forEach(e->installed.add(actual.getChapterType()+":"+e));
        }
        List<Cursor> cursors=repository.all("upgrade_scan_cursors",target.getId(),null,Cursor.class);
        boolean metadata=cursors.stream().anyMatch(c->("chapters:"+target.getId()).equals(c.getId())&&c.isComplete());
        boolean history=cursors.stream().anyMatch(c->("garden:"+target.getId()).equals(c.getId())&&c.getSearchRevision()>=1&&c.isComplete()&&c.getLastFull()>0);
        boolean sources=metadata&&history&&cursors.stream().noneMatch(c->c.isCoverageIncomplete()||c.getError()!=null);
        Set<String> missing=new TreeSet<>(expected); missing.removeAll(eligible); missing.removeAll(installed);
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("targetId",target.getId()); result.put("expectedKnown",metadata&&!expected.isEmpty()); result.put("sourceScanComplete",sources);
        result.put("expectedChapters",expected); result.put("eligibleChapters",eligible); result.put("installedChapters",installed); result.put("missingChapters",missing);
        result.put("qualifyingResources",qualifying); result.put("identityConflicts",identityConflicts); result.put("waitingEvidence",evidencePending);
        result.put("state",!metadata||expected.isEmpty()?"WAITING_METADATA":!sources?"SOURCE_INCOMPLETE":missing.isEmpty()?"COVERED":qualifying==0?"NO_QUALIFYING_RESOURCE":"COVERAGE_GAPS");
        result.put("note","候选覆盖、实际下载完成与媒体入库分别核验；来源失败和待补证条目仍计入范围。");
        return result;
    }
}
