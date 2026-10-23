package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.upgrade.UpgradeModels.Release;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** A separate real, human-labelled gate. Synthetic fixtures cannot satisfy it. */
class UpgradeFrozenAcceptanceTest {
    @Test
    @EnabledIfSystemProperty(named="upgrade.acceptance.dataset",matches=".+")
    void realHumanLabelledFrozenSetMeetsTheDevelopmentPlan() throws Exception {
        Path input=Path.of(System.getProperty("upgrade.acceptance.dataset"));
        JsonObject data=JsonParser.parseString(Files.readString(input,StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("REAL_RELEASES",UpgradeHttp.string(data,"sourceKind"));
        assertFalse(Objects.toString(UpgradeHttp.string(data,"approvedBy"),"").isBlank(),"需要人工审核人");
        java.time.Instant.parse(UpgradeHttp.string(data,"frozenAt"));
        JsonArray development=UpgradeHttp.array(data,"development"), frozen=UpgradeHttp.array(data,"frozen");
        assertFalse(development.isEmpty(),"开发集不能为空"); assertFalse(frozen.isEmpty(),"冻结集不能为空");
        assertTrue(development.size()+frozen.size()>=300,"至少 300 个真实人工标注案例");
        String frozenHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(GsonStatic.toJson(frozen).getBytes(StandardCharsets.UTF_8)));
        assertEquals(frozenHash,UpgradeHttp.string(data,"frozenSha256"),"冻结后输入、标签或分类发生变化");
        Set<String> ids=new HashSet<>(),subjects=new HashSet<>();
        for(JsonArray split:List.of(development,frozen)) for(JsonElement element:split) {
            JsonObject item=element.getAsJsonObject(); String caseId=UpgradeHttp.string(item,"id"),subjectId=UpgradeHttp.string(item,"subjectId");
            assertNotNull(caseId); assertFalse(caseId.isBlank()); assertNotNull(subjectId); assertTrue(subjectId.matches("\\d+"));
            assertTrue(ids.add(caseId),"案例 ID 必须唯一且开发集不能泄漏到冻结集");
            assertFalse(UpgradeHttp.array(item,"sourceReferences").isEmpty(),"需要可回溯的真实发布／Emby 证据引用");
            subjects.add(subjectId);
        }
        assertTrue(subjects.size()>=30,"至少来自 30 部作品");
        var engine=new QualityPolicyEngine(); long sufficient=0, sufficientAutomatic=0,supplementable=0,supplementAutomatic=0,wrongIdentity=0,wrongQuality=0,unexplainedReview=0;
        Map<String,Long> reasons=new TreeMap<>(),categories=new TreeMap<>(); List<Map<String,Object>> failures=new ArrayList<>();
        for(JsonElement element:frozen) {
            JsonObject item=element.getAsJsonObject(); String category=UpgradeHttp.string(item,"category");
            assertTrue(Set.of("SUFFICIENT","SUPPLEMENTABLE","AMBIGUOUS","NO_BETTER","UNAVAILABLE").contains(category),"分类必须在运行前固定"); categories.merge(category,1L,Long::sum);
            Release candidate=GsonStatic.fromJson(item.get("candidate").toString(),Release.class);
            Release current=item.has("current")&&!item.get("current").isJsonNull()?GsonStatic.fromJson(item.get("current").toString(),Release.class):null;
            var policy=GsonStatic.fromJson(item.get("policy").toString(),QualityPolicyEngine.Policy.class);
            if(item.has("recordedModelOutput")) DeepSeekAnalysisClient.applyValidated(List.of(candidate),item.getAsJsonObject("recordedModelOutput"));
            var result=engine.evaluate(policy,candidate,current); reasons.merge(result.reasonCode(),1L,Long::sum);
            boolean automatic=result.verdict()==QualityPolicyEngine.Verdict.UPGRADE, expectedUpgrade=item.get("expectedUpgrade").getAsBoolean();
            if(automatic&&!item.get("sameIdentity").getAsBoolean()) wrongIdentity++;
            if(automatic&&(!item.get("hardConditionsSatisfied").getAsBoolean()||!expectedUpgrade)) wrongQuality++;
            if("SUFFICIENT".equals(category)&&expectedUpgrade) { sufficient++; if(automatic) sufficientAutomatic++; }
            if("SUPPLEMENTABLE".equals(category)&&expectedUpgrade) { supplementable++; if(automatic) supplementAutomatic++; }
            if(Set.of("SUFFICIENT","NO_BETTER").contains(category)&&result.verdict()==QualityPolicyEngine.Verdict.WAITING_EVIDENCE) unexplainedReview++;
            if(automatic!=expectedUpgrade&&Set.of("SUFFICIENT","SUPPLEMENTABLE").contains(category)) failures.add(Map.of("id",UpgradeHttp.string(item,"id"),"expectedUpgrade",expectedUpgrade,"decision",result));
        }
        assertTrue(sufficient>0&&supplementable>0,"冻结集必须包含两类应升级案例，不能通过空分母通过验收");
        double sufficientCoverage=(double)sufficientAutomatic/sufficient,combinedCoverage=(double)(sufficientAutomatic+supplementAutomatic)/(sufficient+supplementable);
        Map<String,Object> report=new LinkedHashMap<>(); report.put("datasetSha256",MediaVerificationService.sha256(input)); report.put("frozenSha256",frozenHash); report.put("approvedBy",UpgradeHttp.string(data,"approvedBy"));
        report.put("frozenCases",frozen.size()); report.put("subjects",subjects.size()); report.put("categories",categories); report.put("reasons",reasons); report.put("wrongIdentityReplacements",wrongIdentity); report.put("wrongQualityReplacements",wrongQuality);
        report.put("sufficientAutomaticCoverage",sufficientCoverage); report.put("combinedAutomaticCoverage",combinedCoverage); report.put("unexplainedReview",unexplainedReview); report.put("failures",failures);
        Files.writeString(Path.of(System.getProperty("upgrade.acceptance.report",input.resolveSibling("upgrade-frozen-report.json").toString())),GsonStatic.toJson(report),StandardCharsets.UTF_8);
        assertEquals(0,wrongIdentity); assertEquals(0,wrongQuality); assertTrue(sufficientCoverage>=0.95); assertTrue(combinedCoverage>=0.90); assertEquals(0,unexplainedReview);
    }
}
