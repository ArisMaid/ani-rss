package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.upgrade.UpgradeModels.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Frozen real release declarations; this checks matching, not media playback or source exhaustiveness. */
@EnabledIfSystemProperty(named="upgrade.frozen.acceptance",matches=".+")
class UpgradeFrozenReplayAcceptanceTest {
    @Test void frozenSubscriptionCandidatesStillMeetTheirHardConditions() throws Exception {
        Path batch=Path.of(System.getProperty("upgrade.frozen.acceptance"));
        JsonObject input=JsonParser.parseString(Files.readString(batch.resolve("frozen-candidates.json"))).getAsJsonObject();
        var policy=GsonStatic.fromJson(input.get("policy").toString(),QualityPolicyEngine.Policy.class); policy.setContainers(Set.of());
        Map<String,Target> targets=new HashMap<>(); for(JsonElement e:input.getAsJsonArray("targets")) { Target target=GsonStatic.fromJson(e.toString(),Target.class); targets.put(target.getSubjectId(),target); }
        Map<String,List<Release>> facts=new HashMap<>();
        for(JsonElement e:input.getAsJsonArray("sourceFacts")) {
            Release fact=new ReleaseAnalysisService().parse(e.getAsJsonObject().get("title").getAsString(),e.getAsJsonObject().get("subjectId").getAsString(),"FROZEN_EVIDENCE");
            facts.computeIfAbsent(fact.getSubjectId(),key->new ArrayList<>()).add(fact);
        }
        targets.forEach((id,target)->SubjectEpisodeResolver.learnSeasonStart(target,facts.getOrDefault(id,List.of())));
        List<Map<String,Object>> failures=new ArrayList<>(),identityPending=new ArrayList<>(),identityConflicts=new ArrayList<>(); int count=0;
        for(JsonElement element:input.getAsJsonArray("candidates")) {
            JsonObject row=element.getAsJsonObject(); Target target=targets.get(row.get("subjectId").getAsString());
            Release release=new ReleaseAnalysisService().parse(row.get("title").getAsString(),target.getSubjectId(),"FROZEN_PUBLIC_RELEASE");
            SubjectEpisodeResolver.normalizeChapters(target,release);
            if(release.getEvidence().stream().noneMatch(e->"SOURCE_SEASON_CONFLICT".equals(e.getSource()))&&release.getEvidence().stream().anyMatch(e->"CHAPTER_IDENTITY_UNRESOLVED".equals(e.getSource())))
                identityPending.add(Map.of("subjectId",target.getSubjectId(),"title",release.getTitle()));
            if(release.getEvidence().stream().anyMatch(e->"SOURCE_SEASON_CONFLICT".equals(e.getSource())))
                identityConflicts.add(Map.of("subjectId",target.getSubjectId(),"title",release.getTitle()));
            // Hard-condition replay and chapter-identity readiness are different measurements.
            release.getEvidence().removeIf(e->Set.of("CHAPTER_IDENTITY_UNRESOLVED","SOURCE_SEASON_CONFLICT").contains(e.getSource()));
            // A single movie/OVA has an explicit subject chapter even without an episode in the title.
            if(release.getEpisodes().isEmpty()&&row.get("standalone").getAsBoolean()) release.getEpisodes().add("1");
            var decision=new QualityPolicyEngine().evaluate(policy,release,null); count++;
            if(decision.verdict()!=QualityPolicyEngine.Verdict.UPGRADE) failures.add(Map.of("title",release.getTitle(),"subjectId",target.getSubjectId(),"verdict",decision.verdict(),"explanation",decision.explanation()));
        }
        Files.writeString(batch.resolve("frozen-replay-result.json"),GsonStatic.toJson(Map.of("cases",count,"passed",count-failures.size(),"failures",failures,"identityPending",identityPending,"identityConflicts",identityConflicts)));
        assertTrue(count>=300); assertTrue(failures.isEmpty(),"See frozen-replay-result.json");
    }
}
