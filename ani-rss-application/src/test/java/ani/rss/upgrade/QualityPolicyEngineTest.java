package ani.rss.upgrade;

import ani.rss.upgrade.UpgradeModels.Release;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QualityPolicyEngineTest {
    private final QualityPolicyEngine engine=new QualityPolicyEngine();
    private final ReleaseAnalysisService parser=new ReleaseAnalysisService();
    private Release release(String group,int resolution,String subtitles) {
        Release r=parser.parse("["+group+"] Example - 01 ["+resolution+"p]["+subtitles+"][MKV]","100","fixture");
        assertEquals(Set.of("1"),r.getEpisodes()); return r;
    }
    @Test void honorsLexicographicPriorityAndHardFloor() {
        var p=new QualityPolicyEngine.Policy(); p.setGroups(List.of("A","B"));
        var decision=engine.evaluate(p,release("A",1080,"CHS"),release("B",2160,"CHS"));
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,decision.verdict()); assertEquals("GROUP",decision.decisiveField());
        p.setMinimumResolution(2160);
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,release("A",1080,"CHS"),release("B",2160,"CHS")).verdict());
    }
    @Test void repairsKnownHardConditionViolationRegardlessOfPreference() {
        var p=new QualityPolicyEngine.Policy(); p.setGroups(List.of("A","B")); p.setMinimumResolution(1080);
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,release("B",1080,"CHS"),release("A",720,"CHS")).verdict());
    }
    @Test void stopsAtDecisiveDimensionAndKeepsUnknownDistinctFromUnlisted() {
        var p=new QualityPolicyEngine.Policy(); p.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.GROUP)); p.setGroups(List.of("A","B"));
        Release next=release("A",2160,"CHS"),old=release("B",1080,"CHS"); next.getGroups().clear(); old.getGroups().clear();
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,next,old).verdict());
        p.setDimensions(List.of(QualityPolicyEngine.Dimension.GROUP,QualityPolicyEngine.Dimension.RESOLUTION));
        assertEquals(QualityPolicyEngine.Verdict.WAITING_EVIDENCE,engine.evaluate(p,next,old).verdict());
        next.getGroups().add("OTHER"); old.getGroups().add("OTHER2");
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,next,old).verdict());
    }
    @Test void equalQualityNeverUpgradesAndCorrectionsAreOptIn() {
        var p=new QualityPolicyEngine.Policy(); Release next=release("A",1080,"CHS"),old=release("A",1080,"CHS"); next.setCorrection(2);
        assertEquals(QualityPolicyEngine.Verdict.NOT_BETTER,engine.evaluate(p,next,old).verdict());
        p.getDimensions().add(QualityPolicyEngine.Dimension.CORRECTION);
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,next,old).verdict());
    }
    @Test void rejectsWrongSubjectSeasonSpecialAndIncompleteBatchCoverage() {
        var p=new QualityPolicyEngine.Policy(); Release next=release("A",2160,"CHS"),old=release("A",1080,"CHS");
        old.setSubjectId("101"); assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,engine.evaluate(p,next,old).verdict());
        old.setSubjectId("100"); old.setSeason(2); next.setSeason(1); assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,engine.evaluate(p,next,old).verdict());
        old.setSeason(1); old.setChapterType("SPECIAL"); assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,engine.evaluate(p,next,old).verdict());
        old.setChapterType("NORMAL"); old.getEpisodes().add("2"); assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,engine.evaluate(p,next,old).verdict());
    }
    @Test void honorsJointGroupsAliasesAndNoOcrHardSubEvidence() {
        var p=new QualityPolicyEngine.Policy(); p.setGroups(List.of("A","B")); p.setGroupAliases(Map.of("AliasA","A"));
        Release next=parser.parse("[AliasA & C] Example - 01 [1080p][简日繁][内嵌][MKV]","100","fixture");
        Release old=release("B",2160,"简日繁");
        assertEquals(Set.of("CHS","CHT","JPN"),next.getLanguages()); assertEquals("HARD",next.getSubtitlePackaging());
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,next,old).verdict());
    }
    @Test void japaneseAudioCannotSatisfyJapaneseSubtitles() {
        Release r=parser.parse("[A] Example - 01 [1080p][CHS][日语音轨][MKV]","100","fixture");
        var p=new QualityPolicyEngine.Policy(); p.setRequiredLanguages(Set.of("JPN"));
        assertEquals(Set.of("CHS"),r.getLanguages()); assertEquals(QualityPolicyEngine.Verdict.REJECTED,engine.evaluate(p,r,null).verdict());
    }
    @Test void genericChineseIsEnoughForChineseFloorButNotForScriptPreference() {
        Release r=release("A",1080,"CHS"); r.getLanguages().clear();
        com.google.gson.JsonObject track=new com.google.gson.JsonObject(); track.addProperty("Language","zho");
        EmbyLibraryIndexService.mergeSubtitle(r,track);
        assertEquals(Set.of("ZHO"),r.getLanguages());
        var p=new QualityPolicyEngine.Policy();
        assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,r,null).verdict());
        assertEquals(QualityPolicyEngine.Verdict.WAITING_EVIDENCE,engine.evaluate(p,r,release("A",1080,"CHS")).verdict());
        p.setRequiredLanguages(Set.of("CHS"));
        assertEquals(QualityPolicyEngine.Verdict.WAITING_EVIDENCE,engine.evaluate(p,r,null).verdict());
        track.addProperty("Title","简体中文"); EmbyLibraryIndexService.mergeSubtitle(r,track);
        assertEquals(Set.of("CHS"),r.getLanguages());
    }
    @Test void japaneseKanjiAndKanaDoNotEstablishChinese() {
        Release r=new Release();
        MediaVerificationService.supplementText(r,"この国の学校と会議では新しい動画を発表します。長い時間の後で画面を見ます。","fixture");
        assertEquals(Set.of("JPN"),r.getLanguages());
        MediaVerificationService.supplementText(r,"这是我们说过的话，书和车都已经进来了。","fixture");
        assertTrue(r.getLanguages().contains("CHS"));
    }
    @Test void alternativeContentEditionsNeverReplaceMainContent() {
        Release main=release("B",1080,"CHS"),cut=parser.parse("[A] Example - 01 [2160p][CHS][MKV] Director's Cut","100","fixture");
        assertEquals("DIRECTORS_CUT",cut.getContentEdition());
        assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,engine.evaluate(new QualityPolicyEngine.Policy(),cut,main).verdict());
        assertEquals("RECAP",parser.parse("[A] Example - 01 总集篇 [1080p]","100","fixture").getContentEdition());
    }
    @Test void movieFilenameNumbersAndExactAbsoluteEpisodeNamesResolveToCanonicalCoverage() {
        UpgradeModels.Target target=new UpgradeModels.Target(); target.setType("Movie");
        Release movie=parser.parse("[A] Example Movie - 01 [1080p][CHS]","100","fixture"); SubjectEpisodeResolver.normalizeChapters(target,movie);
        assertEquals(Set.of("MOVIE"),movie.getEpisodes()); assertEquals("MOVIE",movie.getChapterType());
        target.getChapterTitles().put("NORMAL:1","The New Beginning");
        assertEquals(Optional.of("1"),SubjectEpisodeResolver.exactChapterNumber(target,"The New Beginning","NORMAL"));
        target.getChapterTitles().put("NORMAL:2","The New Beginning");
        assertTrue(SubjectEpisodeResolver.exactChapterNumber(target,"The New Beginning","NORMAL").isEmpty());
    }
    @Test void independentOvaAndOadUseTypedOfficialMainChaptersButNeverConvertExplicitSpecials() {
        for(String type:List.of("OVA","OAD")) {
            UpgradeModels.Target target=new UpgradeModels.Target(); target.setType(type); target.getChapterTypes().put("NORMAL:1","NORMAL"); target.getChapterTypes().put("SPECIAL:1","SPECIAL");
            Release main=parser.parse("[A] Example "+type+" 01 [1080p][CHS]","100","fixture"); assertEquals("SPECIAL",main.getChapterType());
            SubjectEpisodeResolver.normalizeChapters(target,main); assertEquals("NORMAL",main.getChapterType());
            Release special=parser.parse("[A] Example "+type+" SP01 [1080p][CHS]","100","fixture");
            SubjectEpisodeResolver.normalizeChapters(target,special); assertEquals("SPECIAL",special.getChapterType());
        }
    }
    @Test void modelChapterSuggestionRequiresARealIdAndUniqueUpstreamCorroboration() {
        UpgradeModels.Target target=new UpgradeModels.Target(); target.setSeason(2); target.getChapterIds().put("NORMAL:1","5001");
        target.getChapterTitles().put("NORMAL:1","新しい始まり"); target.getChapterAirDates().put("NORMAL:1","2024-04-01");
        var fact=com.google.gson.JsonParser.parseString("{\"name\":\"A new beginning\",\"airDate\":\"2024-04-01\",\"season\":2,\"number\":13}").getAsJsonObject();
        var output=com.google.gson.JsonParser.parseString("{\"id\":\"5001\",\"quote\":\"新しい始まり\"}").getAsJsonObject();
        assertEquals(Optional.of("1"),DeepSeekAnalysisClient.validatedChapterMapping(target,"NORMAL",fact,output));
        output.addProperty("id","invented"); assertTrue(DeepSeekAnalysisClient.validatedChapterMapping(target,"NORMAL",fact,output).isEmpty());
        output.addProperty("id","5001"); fact.addProperty("season",3); assertTrue(DeepSeekAnalysisClient.validatedChapterMapping(target,"NORMAL",fact,output).isEmpty());
        fact.addProperty("season",2); target.getChapterIds().put("NORMAL:2","5002"); target.getChapterAirDates().put("NORMAL:2","2024-04-01");
        assertTrue(DeepSeekAnalysisClient.validatedChapterMapping(target,"NORMAL",fact,output).isEmpty());
    }
    @Test void comparesDeterministicallyAcrossGeneratedOrderingCases() {
        // Synthetic property checks; these are not the 300 manually annotated
        // real-release acceptance set required for a release.
        var p=new QualityPolicyEngine.Policy(); p.setGroups(List.of("A","B","C"));
        List<Release> releases=new ArrayList<>();
        for(String sub:List.of("简日繁","CHS","CHT")) for(String group:List.of("A","B","C")) for(int res:List.of(2160,1080,720)) releases.add(release(group,res,sub));
        int improvements=0;
        for(Release a:releases) for(Release b:releases) {
            var ab=engine.evaluate(p,a,b); assertEquals(ab,engine.evaluate(p,a,b));
            if(ab.verdict()==QualityPolicyEngine.Verdict.UPGRADE) { improvements++; assertEquals(QualityPolicyEngine.Verdict.NOT_BETTER,engine.evaluate(p,b,a).verdict()); }
        }
        assertTrue(improvements>300);
        for(int i=0;i<releases.size()-2;i++) {
            Release a=releases.get(i),b=releases.get(i+1),c=releases.get(i+2);
            if(engine.evaluate(p,a,b).verdict()==QualityPolicyEngine.Verdict.UPGRADE&&engine.evaluate(p,b,c).verdict()==QualityPolicyEngine.Verdict.UPGRADE)
                assertEquals(QualityPolicyEngine.Verdict.UPGRADE,engine.evaluate(p,a,c).verdict());
        }
    }
}
