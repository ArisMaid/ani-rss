package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpgradeSourceContractTest {
    @TempDir Path directory;
    private String prior; private Config original;
    @BeforeEach void before() {
        prior=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        var settings=new UpgradeSettings(); settings.setLibraryIds(List.of("library")); ConfigUtil.CONFIG.setUpgrade(settings);
    }
    @AfterEach void after() { DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }
    @Test void gardenContinuesBeyondTwoHundredAndMergesSourcesWithoutEpisodeDedup() {
        var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
        Target target=new Target(); target.setId("t"); target.setSubjectId("100"); target.setTitle("Example");
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenAnswer(inv->{
                String url=inv.getArgument(0); int page=Integer.parseInt(url.replaceAll(".*[?&]page=(\\d+).*","$1"));
                JsonArray items=new JsonArray(); int count=page<3?100:37;
                for(int i=0;i<count;i++) { int n=(page-1)*100+i; JsonObject item=new JsonObject(); item.addProperty("id",n); item.addProperty("title","[Group] Example - 01 [1080p][CHS][MKV]"); item.addProperty("infoHash",String.format("%040x",n+1)); item.addProperty("magnet","magnet:?xt=urn:btih:"+String.format("%040x",n+1)); items.add(item); }
                JsonObject result=new JsonObject(); result.add("resources",items); return result;
            });
            assertFalse(provider.scanStep(target)); assertFalse(provider.scanStep(target)); assertTrue(provider.scanStep(target));
            assertEquals(237,repository.all("upgrade_releases","t",null,Release.class).size());
            Release duplicate=new ReleaseAnalysisService().parse("[Group] Example - 01 [1080p][CHS][MKV]","100","RSS"); duplicate.setInfoHash(String.format("%040x",1)); duplicate.setTargetId("t"); duplicate.setSourceId("feed:1"); provider.accept(duplicate);
            assertEquals(237,repository.all("upgrade_releases","t",null,Release.class).size()); assertEquals(238,repository.all("upgrade_release_sources",null,null,Map.class).size());
        }
    }
    @Test void gardenExplicitContinuationOverridesAShortPageAndEmptyIncompleteIsAnError() {
        var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
        Target target=new Target(); target.setId("t"); target.setSubjectId("100"); target.setTitle("Example");
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(JsonParser.parseString("{\"resources\":[{\"id\":1,\"title\":\"Example - 01\"}],\"pagination\":{\"complete\":false}}").getAsJsonObject());
            assertFalse(provider.scanStep(target)); assertEquals(1,repository.get("upgrade_scan_cursors","garden:t",Cursor.class).orElseThrow().getOffset());
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(JsonParser.parseString("{\"resources\":[],\"pagination\":{\"complete\":false}}").getAsJsonObject());
            assertThrows(UpgradeHttp.SourceFailure.class,()->provider.scanStep(target));
            assertEquals(1,repository.get("upgrade_scan_cursors","garden:t",Cursor.class).orElseThrow().getOffset());
        }
    }
    @Test void gardenDetailsSupplyFileCoverageWithoutExposingDirectoryPathsOrExecutingLinks() {
        var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
        Release release=new ReleaseAnalysisService().parse("Example Complete","100","ANIMEGARDEN"); release.setTargetId("t"); release.setProvider("dmhy"); release.setProviderId("123"); provider.accept(release);
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get("https://api.animes.garden/detail/dmhy/123",Map.of())).thenReturn(JsonParser.parseString("{\"description\":\"<b>CHS 外挂字幕</b>\",\"files\":[{\"name\":\"folder/Example - 01.mkv\"},{\"name\":\"folder/Example - 02.mkv\"},{\"name\":\"folder/NCOP.mkv\"}]}").getAsJsonObject());
            provider.enrich(release); assertEquals(Set.of("1","2"),release.getEpisodes()); assertTrue(release.getDetailAt()>0);
            assertFalse(release.getAnalysisContext().contains("folder/")); assertTrue(release.getAnalysisContext().contains("CHS 外挂字幕"));
            assertEquals(3,release.getFiles().size());
        }
    }
    @Test void retentionPreservesActiveVersionAndUnfinishedCommitEvidence() {
        var repository=new UpgradeRepository(); repository.event("obsolete","OLD","old decision"); repository.event("active-target","ACTIVE","active version evidence"); repository.event("unfinished","INSTALL","unfinished commit evidence");
        Version version=new Version(); version.setId("version"); version.setTargetId("active-target"); repository.save("upgrade_versions","version","active-target",null,"ACTIVE",1,version);
        Job job=new Job(); job.setId("unfinished"); job.setState(JobState.CLEANUP_PENDING); repository.save("upgrade_jobs","unfinished","other",null,"CLEANUP_PENDING",1,job);
        DatabaseManager.withConnection(c->{ try(var statement=c.prepareStatement("UPDATE upgrade_events SET updated_at=1")) { statement.executeUpdate(); return null; } });
        assertEquals(1,repository.pruneExpiredEvents(System.currentTimeMillis())); assertTrue(repository.all("upgrade_events","obsolete",null,Map.class).isEmpty());
        assertEquals(1,repository.all("upgrade_events","active-target",null,Map.class).size()); assertEquals(1,repository.all("upgrade_events","unfinished",null,Map.class).size());
    }
    @Test void aiCacheExpiresAndCoverageKeepsPausedTargetsInItsDenominator() {
        var repository=new UpgradeRepository();
        repository.save("upgrade_ai_cache","cached","model","cached","CACHED",1,Map.of("items",List.of()));
        assertTrue(repository.get("upgrade_ai_cache","cached",JsonObject.class).isPresent());
        DatabaseManager.withConnection(c->{ try(var statement=c.prepareStatement("UPDATE upgrade_ai_cache SET updated_at=1 WHERE id='cached'")) { statement.executeUpdate(); return null; } });
        assertTrue(repository.get("upgrade_ai_cache","cached",JsonObject.class).isEmpty());
        Target active=new Target(); active.setId("active"); active.setSubjectId("100"); active.setLibraryId("lib"); repository.save("upgrade_targets","active","lib","100:lib","MONITORING",1,active);
        Target paused=new Target(); paused.setId("paused"); paused.setSubjectId("101"); paused.setLibraryId("lib"); paused.setExcluded(true); repository.save("upgrade_targets","paused","lib","101:lib","PAUSED",1,paused);
        Release release=new Release(); release.setTargetId("active"); release.setState("WAITING_AI"); release.setReason("AI_DAILY_BUDGET_EXHAUSTED"); repository.save("upgrade_releases","release","active",null,"WAITING_AI",1,release);
        assertEquals(2,repository.coverageMetrics().get("totalTargets")); assertEquals(1,repository.coverageMetrics().get("discoveredResources")); assertEquals(1,repository.coverageMetrics().get("aiBudgetBlocked"));
    }
    @Test void provisionalResourceGainsHashWithoutDuplicatingAndFailureStatusSurvivesSave() {
        var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
        Release first=new ReleaseAnalysisService().parse("[A] Example - 01 [1080p][CHS]","100","RSS"); first.setTargetId("t"); first.setSourceId("guid-1");
        String id=provider.accept(first).getId();
        Release resolved=new ReleaseAnalysisService().parse(first.getTitle(),"100","RSS"); resolved.setTargetId("t"); resolved.setSourceId("guid-1"); resolved.setInfoHash("a".repeat(40));
        Release canonical=provider.accept(resolved); assertEquals(id,canonical.getId());
        canonical.setState("UNAVAILABLE"); canonical.setRetryAt(123456789); provider.accept(canonical);
        assertEquals(1,repository.all("upgrade_releases","t",null,Release.class).size());
        assertEquals("UNAVAILABLE",repository.get("upgrade_releases",id,Release.class).orElseThrow().getState());
        assertEquals(123456789,repository.get("upgrade_releases",id,Release.class).orElseThrow().getRetryAt());
    }
    @Test void mikanFeedsAreReusedBetweenDailyDiscoveryPasses() {
        var repository=new UpgradeRepository(); var provider=new ReleaseProvider(repository,new ReleaseAnalysisService());
        Target target=new Target(); target.setId("t"); target.setSubjectId("100");
        Cursor cursor=new Cursor(); cursor.setId("mikan:t"); cursor.setLastSuccess(System.currentTimeMillis()); cursor.getDiscoveredFeeds().add("https://mikan.example/rss/100");
        repository.save("upgrade_scan_cursors",cursor.getId(),target.getId(),null,"COMPLETE",1,cursor);
        try(var feeds=mockStatic(ani.rss.util.other.ItemsUtil.class)) {
            feeds.when(()->ani.rss.util.other.ItemsUtil.getItemsRaw("https://mikan.example/rss/100")).thenReturn(List.of(new ani.rss.util.other.ItemsUtil.RawRelease("id","[A] Example - 01 [1080p][CHS]","magnet:?xt=urn:btih:"+"b".repeat(40))));
            provider.rss(target); assertEquals(1,repository.all("upgrade_releases","t",null,Release.class).size());
        }
    }
    @Test void catalogSplitsMonthlyWindowAndDoesNotInventIncompletePremiere() {
        var repository=new UpgradeRepository(); Campaign campaign=new Campaign(); campaign.setId("c"); campaign.setStartDate("2024-01-01"); campaign.setEndDate("2024-01-31"); campaign.setLibraryId("library");
        var provider=new SubjectCatalogProvider(repository);
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.post(anyString(),anyMap(),any())).thenReturn(JsonParser.parseString("{\"total\":1000,\"data\":[]}").getAsJsonObject());
            assertFalse(provider.scanStep(campaign)); assertTrue(repository.get("upgrade_scan_cursors","catalog:c",Cursor.class).orElseThrow().isDaily());
            http.when(()->UpgradeHttp.post(anyString(),anyMap(),any())).thenReturn(JsonParser.parseString("{\"total\":1,\"data\":[{\"id\":1,\"name\":\"unknown date\",\"date\":\"\"}]}").getAsJsonObject());
            assertFalse(provider.scanStep(campaign)); Target target=repository.all("upgrade_targets",null,null,Target.class).get(0);
            assertNull(target.getPremiereDate()); assertEquals("WAITING_PREMIERE_EVIDENCE",target.getState());
        }
    }
    @Test void failedEmbyPagePreservesLastCompleteBatchAndDoesNotGrantFirstDownloadFreshness() {
        var settings=new UpgradeSettings(); settings.setEmbyUrl("http://emby.test"); settings.setEmbyApiKey("test-only"); settings.setLibraryIds(List.of("library")); ConfigUtil.CONFIG.setUpgrade(settings);
        var repository=new UpgradeRepository(); var emby=new EmbyLibraryIndexService(repository,new LibraryPathMappingService(),new ReleaseAnalysisService());
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(JsonParser.parseString("{\"TotalRecordCount\":1,\"Items\":[{\"Id\":\"old\",\"Type\":\"Movie\"}]}").getAsJsonObject());
            assertTrue(emby.scanStep("library")); String batch=repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow().getCompletedBatch();
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_500",true));
            assertThrows(UpgradeHttp.SourceFailure.class,()->emby.scanStep("library"));
            assertEquals(batch,repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow().getCompletedBatch());
            assertTrue(repository.get("emby_library_items",batch+":old",JsonObject.class).isPresent());
        }
    }
    @Test void metadataIsMissingOnlyLockedAndProviderConflictsArePreserved() throws Exception {
        Target target=new Target(); target.setId("t"); target.setSubjectId("100"); target.setTitle("official title"); target.setSummary("official summary"); target.setType("TV");
        Path nfo=directory.resolve("tvshow.nfo"); Files.writeString(nfo,"<tvshow><title>User title</title><lockedfields>plot</lockedfields></tvshow>");
        var fields=MetadataEnrichmentService.supplementNfo(nfo,target,"operation");
        String text=Files.readString(nfo); assertTrue(text.contains("User title")); assertFalse(text.contains("official title")); assertFalse(text.contains("official summary")); assertTrue(fields.contains("bangumi"));
        Files.writeString(nfo,"<tvshow><lockdata>true</lockdata><title>Locked</title></tvshow>"); assertTrue(MetadataEnrichmentService.supplementNfo(nfo,target,"operation2").isEmpty());
        Files.writeString(nfo,"<tvshow><uniqueid type=\"bangumi\">999</uniqueid></tvshow>"); assertThrows(java.io.IOException.class,()->MetadataEnrichmentService.supplementNfo(nfo,target,"operation3"));
    }
    @Test void realFfprobeAndThreeDecodeSamplesValidateSmallFixture() throws Exception {
        var verifier=new MediaVerificationService(); Assumptions.assumeTrue(verifier.available("ffmpeg")&&verifier.available("ffprobe"));
        Path video=directory.resolve("fixture-01.mkv");
        MediaVerificationService.run(List.of("ffmpeg","-v","error","-nostdin","-f","lavfi","-i","color=c=black:s=1280x720:r=5","-t","6","-c:v","libx264","-threads","1",video.toString()),30,100000);
        Release declared=new ReleaseAnalysisService().parse("[A] Example - 01 [720p][CHS]","100","TEST");
        var verified=verifier.verify(video,declared,new UpgradeSettings()); assertEquals(720,verified.attributes().getResolution()); assertEquals("mkv",verified.attributes().getContainer()); assertEquals(64,verified.sha256().length());
    }
    @Test void actualJapaneseSoftSubtitleCannotRetainAFalseChineseDeclarationOrOverrideHardSubEvidence() throws Exception {
        var verifier=new MediaVerificationService(); Assumptions.assumeTrue(verifier.available("ffmpeg")&&verifier.available("ffprobe"));
        Path subtitle=Files.writeString(directory.resolve("japanese.srt"),"1\n00:00:00,000 --> 00:00:05,000\nこれは日本語の字幕です。新しい物語が始まります。\n");
        Path video=directory.resolve("japanese-01.mkv");
        MediaVerificationService.run(List.of("ffmpeg","-v","error","-nostdin","-f","lavfi","-i","color=c=black:s=1280x720:r=5","-i",subtitle.toString(),"-t","6","-map","0:v","-map","1:s","-c:v","libx264","-threads","1","-c:s","srt","-metadata:s:s:0","language=jpn",video.toString()),30,100000);
        Release declared=new ReleaseAnalysisService().parse("[A] Example - 01 [720p][CHS][内封]","100","TEST");
        var actual=verifier.verify(video,declared,new UpgradeSettings()).attributes();
        assertEquals(Set.of("JPN"),actual.getLanguages()); assertEquals(QualityPolicyEngine.Verdict.REJECTED,new QualityPolicyEngine().evaluate(new QualityPolicyEngine.Policy(),actual,null).verdict());
        assertTrue(actual.getEvidence().stream().anyMatch(e->"subtitleDeclarationConflict".equals(e.getField())));
        declared.setSubtitlePackaging("HARD"); actual=verifier.verify(video,declared,new UpgradeSettings()).attributes();
        assertEquals(Set.of("CHS","JPN"),actual.getLanguages());
    }
    @Test void readableEmbeddedChineseTextCorrectsAWrongJapaneseLanguageTag() throws Exception {
        var verifier=new MediaVerificationService(); Assumptions.assumeTrue(verifier.available("ffmpeg")&&verifier.available("ffprobe"));
        Path subtitle=Files.writeString(directory.resolve("chinese.srt"),"1\n00:00:00,000 --> 00:00:05,000\n这是我们说过的话，书和车都已经进来了。\n");
        Path video=directory.resolve("chinese-01.mkv");
        MediaVerificationService.run(List.of("ffmpeg","-v","error","-nostdin","-f","lavfi","-i","color=c=black:s=1280x720:r=5","-i",subtitle.toString(),"-t","6","-map","0:v","-map","1:s","-c:v","libx264","-threads","1","-c:s","srt","-metadata:s:s:0","language=jpn",video.toString()),30,100000);
        Release declared=new ReleaseAnalysisService().parse("[A] Example - 01 [720p][简日][内封]","100","TEST");
        Release actual=verifier.verify(video,declared,new UpgradeSettings()).attributes(); assertEquals(Set.of("CHS"),actual.getLanguages());
        var policy=new QualityPolicyEngine.Policy(); policy.setRequiredLanguages(Set.of("JPN"));
        assertEquals(QualityPolicyEngine.Verdict.REJECTED,new QualityPolicyEngine().evaluate(policy,actual,null).verdict());
    }
    @Test void completionRetainsIndependentIdentityAndManifestWithoutInventingPremiere() throws Exception {
        Path before=Files.createDirectory(directory.resolve("before")),after=Files.createDirectory(directory.resolve("after"));
        var subscription=new ani.rss.entity.Ani(); subscription.setId("completed"); subscription.setBgmUrl("https://bgm.tv/subject/100"); subscription.setTitle("Example"); subscription.setUrl("https://rss.example/feed");
        UpgradeFileCoordinator.migrated(subscription,before.toString(),after.toString());
        var repository=new UpgradeRepository(); Target saved=repository.identity("upgrade_targets","100:UNASSIGNED",Target.class).orElseThrow();
        assertEquals("WAITING_LIBRARY",saved.getState()); assertEquals(after.toString(),saved.getOutputDirectory()); assertNull(saved.getPremiereDate()); assertTrue(saved.isFinished());
        JsonObject archive=repository.get("upgrade_events","completion-archive:completed",JsonObject.class).orElseThrow(); assertTrue(archive.has("files")); assertFalse(archive.get("premiereVerified").getAsBoolean());
    }
    @Test void existingNfoProvidesBoundedGroupsAndCannotReadExternalEntities() throws Exception {
        Path video=Files.writeString(directory.resolve("episode.mkv"),"fixture"); Path nfo=directory.resolve("episode.nfo");
        Files.writeString(nfo,"<episodedetails><uniqueid type=\"bangumi\">100</uniqueid><releasegroup>A &amp; B</releasegroup><subtitle>CHS</subtitle></episodedetails>");
        var evidence=ExistingMetadataEvidence.read(video,directory,false); assertEquals("100",evidence.bangumiId()); assertEquals(Set.of("A","B"),evidence.groups()); assertTrue(evidence.text().contains("CHS"));
        Files.writeString(nfo,"<!DOCTYPE x [<!ENTITY remote SYSTEM 'file:///missing-secret'>]><episodedetails><releasegroup>&remote;</releasegroup></episodedetails>");
        assertThrows(java.io.IOException.class,()->ExistingMetadataEvidence.read(video,directory,false));
    }
}
