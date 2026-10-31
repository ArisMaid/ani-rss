package ani.rss.upgrade;

import ani.rss.entity.Config;
import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.service.MikanService;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MikanQuarterCatalogTest {
    @TempDir Path directory;
    String prior; Config original; UpgradeRepository repository; MikanService mikan; MikanQuarterCatalog catalog;
    @BeforeEach void before() {
        prior=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        ConfigUtil.CONFIG.setUpgrade(new UpgradeSettings()); ConfigUtil.CONFIG.getUpgrade().setLibraryIds(List.of("library")); ConfigUtil.CONFIG.setMikanHost("https://mikanani.me");
        repository=new UpgradeRepository(); mikan=mock(MikanService.class); catalog=new MikanQuarterCatalog(mikan,repository);
        when(mikan.catalog(any())).thenReturn(directory(1));
    }
    @AfterEach void after() { DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }
    Mikan directory(int size) {
        var items=new ArrayList<MikanInfo>(); for(int i=1;i<=size;i++) items.add(new MikanInfo().setTitle("Animation "+i).setUrl("https://mikanani.me/Home/Bangumi/"+i));
        return new Mikan().setSeasons(List.of(new Mikan.Season().setYear(2024).setSeason("春"),new Mikan.Season().setYear(2013).setSeason("秋")))
                .setWeeks(List.of(new Mikan.Week().setWeekLabel("OVA").setItems(items))).setTotalItem(size);
    }
    Campaign campaign() {
        Campaign c=new Campaign(); c.setId("c"); c.setName("2024 春"); c.setCatalogSource("MIKAN"); c.setMikanYear(2024); c.setMikanSeason("春"); c.setLibraryId("library"); MikanQuarterCatalog.normalize(c); return c;
    }
    String html(int id) { return "<div class='bangumi-info'>Bangumi番组计划链接：<a href='https://bgm.tv/subject/"+(100+id)+"'>Bgm</a></div><a href='/RSS/Bangumi?bangumiId="+id+"'>RSS</a>"; }
    JsonObject subject(int id,String date) { return JsonParser.parseString("{\"id\":"+(100+id)+",\"type\":2,\"name\":\"Animation "+id+"\",\"date\":\""+date+"\",\"platform\":\"OVA\"}").getAsJsonObject(); }
    @Test void actualMenuRejectsUnavailableQuarterAndKeepsAllCategoriesInPreview() {
        assertEquals(2,catalog.seasons().size());
        assertThrows(IllegalArgumentException.class,()->catalog.preview(2013,"春"));
        var entries=catalog.preview(2024,"春"); assertEquals(1,entries.size()); assertEquals("OVA",entries.get(0).getCategory());
        verify(mikan,never()).list(any(),any());
    }
    @Test void canonicalDatesIgnoreClientRangeAndLegacyDatesStayLiteral() {
        Campaign c=campaign(); c.setStartDate("1990-01-01"); MikanQuarterCatalog.normalize(c);
        assertEquals("2024-04-01",c.getStartDate()); assertEquals("2024-06-30",c.getEndDate());
        c.setMikanSeason("冬"); MikanQuarterCatalog.normalize(c); assertEquals("2024-03-31",c.getEndDate());
        c.setCatalogSource(null); c.setStartDate("2024-02-17"); c.setEndDate("2024-05-19"); MikanQuarterCatalog.normalize(c);
        assertEquals("2024-02-17",c.getStartDate()); assertEquals("2024-05-19",c.getEndDate()); assertNull(c.getMikanYear());
        c.setCatalogSource("MIKAN"); c.setMikanYear(2024); c.setMikanSeason("Q4"); assertThrows(IllegalArgumentException.class,()->MikanQuarterCatalog.normalize(c));
    }
    @Test void previewDeduplicatesMikanIdsAndRejectsAnUnexpectedOrigin() {
        Mikan data=directory(1); data.getWeeks().get(0).getItems().add(data.getWeeks().get(0).getItems().get(0)); when(mikan.catalog(any())).thenReturn(data);
        assertEquals(1,catalog.preview(2024,"春").size());
        data.getWeeks().get(0).getItems().get(0).setUrl("https://example.com/Home/Bangumi/1"); assertThrows(UpgradeHttp.SourceFailure.class,()->catalog.preview(2024,"春"));
    }
    @Test void verifiedBindingRejectsAmbiguousIdentityAndUnrelatedFeeds() {
        var binding=MikanQuarterCatalog.binding(html(1)+"<a href='/RSS/Bangumi?bangumiId=1&amp;subgroupid=9'>Group RSS</a>","https://mikanani.me/Home/Bangumi/1"); assertEquals("101",binding.subjectId()); assertEquals(1,binding.feeds().size());
        assertThrows(UpgradeHttp.SourceFailure.class,()->MikanQuarterCatalog.binding(html(2),"https://mikanani.me/Home/Bangumi/1"));
        assertThrows(UpgradeHttp.SourceFailure.class,()->MikanQuarterCatalog.binding(html(1)+"<div class='bangumi-info'><a href='https://bangumi.tv/subject/999'>Other</a></div>","https://mikanani.me/Home/Bangumi/1"));
        assertThrows(UpgradeHttp.SourceFailure.class,()->MikanQuarterCatalog.binding(html(1).replace("https://bgm.tv","https://untrusted.example"),"https://mikanani.me/Home/Bangumi/1"));
    }
    @Test void savePersistsSelectionAndAllowsPauseDuringAnUpstreamOutage() {
        var service=new UpgradeCampaignService(repository,new QualityPolicyEngine(),catalog); var policy=service.savePolicy(null,new QualityPolicyEngine.Policy());
        Campaign c=campaign(); c.setPolicyId(policy.getId()); c.setStartDate("1900-01-01"); var saved=service.saveCampaign(null,c);
        assertEquals("2024-04-01",saved.getStartDate()); assertEquals("春",repository.get("upgrade_campaigns",saved.getId(),Campaign.class).orElseThrow().getMikanSeason());
        when(mikan.catalog(any())).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_UNAVAILABLE",true));
        saved.setEnabled(false); assertFalse(service.saveCampaign(saved.getId(),saved).isEnabled());
        saved.setMikanSeason("夏"); assertThrows(UpgradeHttp.SourceFailure.class,()->service.saveCampaign(saved.getId(),saved));
    }
    @Test void cancellationSurvivesRestartAndBothDiscoveryPathsWithoutGlobalExclusion() {
        when(mikan.catalog(any())).thenReturn(directory(2));
        var service=new UpgradeCampaignService(repository,new QualityPolicyEngine(),catalog);
        var policy=service.savePolicy(null,new QualityPolicyEngine.Policy());
        Campaign c=campaign(); c.setPolicyId(policy.getId()); c.setExcludedMikanSubjects(Map.of("1","101"));
        c=service.saveCampaign(null,c);
        DatabaseManager.reopen();
        Campaign restored=repository.get("upgrade_campaigns",c.getId(),Campaign.class).orElseThrow();
        assertEquals(Map.of("1","101"),restored.getExcludedMikanSubjects());
        var subjects=new SubjectCatalogProvider(repository,catalog);
        assertNull(subjects.accept(restored,subject(1,"2024-04-03"),"BANGUMI_SEARCH"));
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.getText(anyString())).thenReturn(html(2));
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(subject(2,"2024-04-03"));
            assertTrue(catalog.scanStep(restored,subjects));
            http.verify(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/1"),never());
            assertTrue(repository.identity("upgrade_targets","101:library",Target.class).isEmpty());
            Target selected=repository.identity("upgrade_targets","102:library",Target.class).orElseThrow();
            assertTrue(service.eligible(selected));
        }
        Target existing=new Target(); existing.setId("old"); existing.setSubjectId("101"); existing.setLibraryId("library"); existing.setPremiereDate("2024-04-03");
        repository.save("upgrade_targets","old","library","101:library","MONITORING",1,existing);
        assertFalse(service.eligible(existing));
        Campaign second=campaign(); second.setPolicyId(policy.getId()); service.saveCampaign(null,second);
        assertTrue(service.eligible(existing)); assertFalse(existing.isExcluded());
    }
    @Test void cancellationBindingUsesOnlyConfiguredOriginAndReportsSourceFailure() {
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/1")).thenReturn(html(1));
            assertEquals("101",catalog.exclusionSubject("1"));
            assertThrows(IllegalArgumentException.class,()->catalog.exclusionSubject("../admin"));
            http.when(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/2")).thenReturn("<html>No identity</html>");
            assertThrows(UpgradeHttp.SourceFailure.class,()->catalog.exclusionSubject("2"));
        }
    }
    @Test void editingCancellationInvalidatesPendingTargetDecisionsAndAllowsResubscribe() {
        var service=new UpgradeCampaignService(repository,new QualityPolicyEngine(),catalog);
        var policy=service.savePolicy(null,new QualityPolicyEngine.Policy());
        Campaign c=campaign(); c.setPolicyId(policy.getId()); c=service.saveCampaign(null,c);
        Target t=new Target(); t.setId("kept"); t.setSubjectId("101"); t.setLibraryId("library"); t.setPremiereDate("2024-04-03");
        repository.save("upgrade_targets","kept","library","101:library","MONITORING",1,t);
        c.setExcludedMikanSubjects(Map.of("1","101")); c=service.saveCampaign(c.getId(),c);
        assertFalse(service.eligible(t)); assertTrue(service.excludedBySelection(t));
        assertEquals(2,repository.get("upgrade_targets","kept",Target.class).orElseThrow().getRevision());
        c.setExcludedMikanSubjects(Map.of()); service.saveCampaign(c.getId(),c);
        assertTrue(service.eligible(t)); assertEquals(3,repository.get("upgrade_targets","kept",Target.class).orElseThrow().getRevision());
    }
    @Test void boundedDiscoveryResumesPersistedSnapshotWithoutRefetchingAlreadyVisitedTitles() {
        when(mikan.catalog(any())).thenReturn(directory(5)); Campaign c=campaign(); var subjects=new SubjectCatalogProvider(repository,catalog);
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.getText(anyString())).thenAnswer(inv->html(Integer.parseInt(((String)inv.getArgument(0)).replaceAll(".*/",""))));
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenAnswer(inv->subject(Integer.parseInt(((String)inv.getArgument(0)).replaceAll(".*/",""))-100,"2024-04-03"));
            assertFalse(catalog.scanStep(c,subjects)); assertEquals(3,repository.all("upgrade_targets",null,null,Target.class).size());
            var resumed=new MikanQuarterCatalog(mikan,new UpgradeRepository()); assertTrue(resumed.scanStep(c,subjects)); assertEquals(5,repository.all("upgrade_targets",null,null,Target.class).size());
            http.verify(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/1"),times(1));
            verify(mikan,times(2)).catalog(any()); // Menu plus directory, no cold score or subscription path.
        }
    }
    @Test void premiereScopeExcludesLongRunningShowsAndPreservesExclusionsAndManualFeeds() {
        when(mikan.catalog(any())).thenReturn(directory(3)); Campaign c=campaign(); var subjects=new SubjectCatalogProvider(repository,catalog);
        Target priorTarget=new Target(); priorTarget.setId("prior"); priorTarget.setSubjectId("102"); priorTarget.setLibraryId("library"); priorTarget.setExcluded(true); priorTarget.setRssUrls(List.of("https://example.com/manual.xml")); repository.save("upgrade_targets","prior","library","102:library","MONITORING",1,priorTarget);
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.getText(anyString())).thenAnswer(inv->html(Integer.parseInt(((String)inv.getArgument(0)).replaceAll(".*/",""))));
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenAnswer(inv->{ int id=Integer.parseInt(((String)inv.getArgument(0)).replaceAll(".*/",""))-100; return subject(id,id==1?"1996-01-08":id==3?"unknown":"2024-04-03"); });
            assertTrue(catalog.scanStep(c,subjects)); assertTrue(repository.identity("upgrade_targets","101:library",Target.class).isEmpty());
            Target kept=repository.get("upgrade_targets","prior",Target.class).orElseThrow(); assertTrue(kept.isExcluded()); assertEquals(2,kept.getRssUrls().size()); assertTrue(kept.getAliases().contains("Animation 2"));
            assertEquals("WAITING_PREMIERE_EVIDENCE",repository.identity("upgrade_targets","103:library",Target.class).orElseThrow().getState());
        }
    }
    @Test void retryDoesNotAdvanceTheFailedItemAndSchemaFailureStaysVisibleInCombinedCoverage() {
        when(mikan.catalog(any())).thenReturn(directory(2)); Campaign c=campaign(); var subjects=new SubjectCatalogProvider(repository,catalog);
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/1")).thenReturn(html(1));
            http.when(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/2")).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_UNAVAILABLE",true));
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(subject(1,"2024-04-03"));
            assertThrows(UpgradeHttp.SourceFailure.class,()->subjects.scanStep(c)); assertEquals(1,repository.get("upgrade_scan_cursors","mikan-catalog:c",Cursor.class).orElseThrow().getOffset());
            http.when(()->UpgradeHttp.getText("https://mikanani.me/Home/Bangumi/2")).thenReturn("<html>Missing identity</html>");
            http.when(()->UpgradeHttp.post(anyString(),anyMap(),any())).thenReturn(JsonParser.parseString("{\"data\":[],\"total\":0}").getAsJsonObject());
            assertFalse(subjects.scanStep(c)); assertFalse(subjects.scanStep(c)); assertTrue(subjects.scanStep(c));
            Cursor finalCursor=repository.get("upgrade_scan_cursors","catalog:c",Cursor.class).orElseThrow(); assertTrue(finalCursor.isCoverageIncomplete()); assertTrue(finalCursor.getError().contains("MIKAN_SUBJECT_LINK_MISSING"));
        }
    }
}
