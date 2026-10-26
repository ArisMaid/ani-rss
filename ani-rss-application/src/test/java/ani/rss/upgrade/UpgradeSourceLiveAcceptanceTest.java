package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real public provider queries, fresh isolated history, no manually configured RSS. */
@EnabledIfSystemProperty(named="upgrade.source.acceptance",matches=".+")
class UpgradeSourceLiveAcceptanceTest {
    @Test void completeAutomaticSubjectAndRegisteredAliasSearch() throws Exception {
        Path batch=Path.of(System.getProperty("upgrade.source.acceptance")).toAbsolutePath().normalize();
        String prior=System.getProperty("CONFIG"); var config=ConfigUtil.snapshot();
        Path run=Files.createDirectory(batch.resolve("source-run-"+System.currentTimeMillis()));
        System.setProperty("CONFIG",run.resolve("config").toString()); DatabaseManager.close(); ConfigUtil.CONFIG.setProxy(false).setUpgrade(new UpgradeSettings());
        List<Map<String,Object>> outcomes=new ArrayList<>();
        try {
            var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
            for(Target target:GsonStatic.fromJsonList(Files.readString(batch.resolve("source-metadata.json")),Target.class)) {
                target.setRssUrls(new ArrayList<>()); // Test only automatic identity and aliases from metadata.
                Map<String,Object> result=new LinkedHashMap<>(); result.put("subjectId",target.getSubjectId()); result.put("title",target.getTitle());
                try {
                    boolean complete=false; int pages=0;
                    while(!complete&&pages++<32) complete=provider.scanStep(target);
                    result.put("pages",pages); result.put("complete",complete);
                    var policy=QualityPolicyPresets.catalog().presets().get(0).policy(); policy.setContainers(Set.of());
                    int accepted=0;
                    for(Release release:repository.all("upgrade_releases",target.getId(),null,Release.class)) {
                        SubjectEpisodeResolver.normalizeChapters(target,release);
                        if(new QualityPolicyEngine().evaluate(policy,release,null).verdict()==QualityPolicyEngine.Verdict.UPGRADE) accepted++;
                    }
                    result.put("qualifyingResources",accepted); result.put("passed",complete&&accepted>0);
                } catch(Exception error) { result.put("passed",false); result.put("error",error.getClass().getSimpleName()); }
                outcomes.add(result); Files.writeString(run.resolve("result.json"),GsonStatic.toJson(outcomes));
                System.out.println("SOURCE_ACCEPTANCE "+GsonStatic.toJson(result));
            }
            assertTrue(outcomes.stream().allMatch(o->Boolean.TRUE.equals(o.get("passed"))),"See isolated source result.json");
        } finally { DatabaseManager.close(); cn.hutool.core.bean.BeanUtil.copyProperties(config,ConfigUtil.CONFIG); if(prior==null)System.clearProperty("CONFIG");else System.setProperty("CONFIG",prior); }
    }
}
