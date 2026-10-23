package ani.rss.upgrade;

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

class UpgradeEmbyIndexTest {
    @TempDir Path directory;
    private String prior;
    private Config original;
    private UpgradeRepository repository;
    private EmbyLibraryIndexService emby;

    @BeforeEach void before() {
        prior=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        var settings=new UpgradeSettings(); settings.setEmbyUrl("http://emby.test"); settings.setEmbyApiKey("test-only"); settings.setLibraryIds(List.of("library"));
        settings.setEmbyMappings(List.of(new UpgradeSettings.PathMapping("/media",directory.toString()))); ConfigUtil.CONFIG.setUpgrade(settings);
        repository=new UpgradeRepository(); emby=new EmbyLibraryIndexService(repository,new LibraryPathMappingService(),new ReleaseAnalysisService());
    }
    @AfterEach void after() { DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }

    @Test void failedIncrementalPageCannotChangeLastSealedSnapshotAndRecoveryRetainsUnchangedItems() {
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(2,item("old","Original"),item("unchanged","Keep")));
            assertTrue(emby.scanStep("library"));
            String batch=repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow().getCompletedBatch();
            JsonObject[] changes=new JsonObject[200]; changes[0]=item("old","Changed"); for(int i=1;i<200;i++) changes[i]=item("change"+i,"New");
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(201,changes));
            assertFalse(emby.scanStep("library"));
            assertEquals("Original",UpgradeHttp.string(repository.get("emby_library_items",batch+":old",JsonObject.class).orElseThrow(),"Name"));
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_500",true));
            assertThrows(UpgradeHttp.SourceFailure.class,()->emby.scanStep("library")); assertFalse(emby.fresh("library"));
            assertEquals(batch,repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow().getCompletedBatch());
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(201,item("last","Last")));
            assertTrue(emby.scanStep("library")); assertTrue(emby.fresh("library"));
            String next=repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow().getCompletedBatch();
            assertNotEquals(batch,next);
            assertEquals("Changed",UpgradeHttp.string(repository.get("emby_library_items",next+":old",JsonObject.class).orElseThrow(),"Name"));
            assertTrue(repository.get("emby_library_items",next+":unchanged",JsonObject.class).isPresent());
        }
    }

    @Test void seriesProviderIdDoesNotAdoptOtherSeasonAndInstalledPathBindsWithoutDuplicateVersion() throws Exception {
        Files.writeString(directory.resolve("first.mkv"),"first"); Files.writeString(directory.resolve("second.mkv"),"second");
        JsonObject series=item("series","Example"); series.addProperty("Type","Series"); series.addProperty("PremiereDate","2024-01-01T00:00:00Z"); series.add("ProviderIds",JsonParser.parseString("{\"Bangumi\":\"100\"}"));
        JsonObject season1=season("season1",1,"2024-01-01T00:00:00Z"), season2=season("season2",2,"2024-07-01T00:00:00Z");
        Target target=new Target(); target.setId("target"); target.setSubjectId("100"); target.setLibraryId("library"); target.setTitle("Example"); target.setPremiereDate("2024-01-01"); target.setPremiereUpper("2024-01-01");
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(5,series,season1,season2,episode("one",1,"first.mkv"),episode("two",2,"second.mkv")));
            assertTrue(emby.scanStep("library"));
            List<Version> adopted=emby.adopt(target); assertEquals(1,adopted.size()); assertEquals("one",adopted.get(0).getEmbyItemId()); assertEquals(1,target.getSeason());
            // Reindex a path installed by this feature; bind the Emby IDs to its
            // durable version instead of manufacturing a second active version.
            Version current=adopted.get(0); current.setOrigin("AUTOMATIC_UPGRADE"); current.setProtectedVersion(true);
            repository.save("upgrade_versions",current.getId(),target.getId(),null,"ACTIVE",1,current);
            assertEquals(1,emby.adopt(target).size()); assertEquals(1,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
            assertTrue(repository.get("upgrade_versions",current.getId(),Version.class).orElseThrow().isProtectedVersion());
        }
    }

    @Test void completedFullScanRetiresMissingVersionButFailedScanDoesNot() throws Exception {
        Files.writeString(directory.resolve("first.mkv"),"first");
        Target target=new Target(); target.setId("target"); target.setSubjectId("100"); target.setLibraryId("library"); target.setTitle("Example");
        JsonObject movie=item("movie","Example"); movie.addProperty("Type","Movie"); movie.add("ProviderIds",JsonParser.parseString("{\"Bangumi\":\"100\"}")); movie.add("MediaSources",JsonParser.parseString("[{\"Id\":\"source\",\"Path\":\"/media/first.mkv\"}]"));
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(1,movie)); assertTrue(emby.scanStep("library")); assertEquals(1,emby.adopt(target).size());
            Cursor cursor=repository.get("upgrade_scan_cursors","emby:library",Cursor.class).orElseThrow(); cursor.setLastFull(1); repository.save("upgrade_scan_cursors",cursor.getId(),"library",null,"COMPLETE",1,cursor);
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_500",true)); assertThrows(UpgradeHttp.SourceFailure.class,()->emby.scanStep("library"));
            assertEquals(1,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
            http.when(()->UpgradeHttp.get(anyString(),anyMap())).thenReturn(page(0)); assertTrue(emby.scanStep("library")); emby.adopt(target);
            assertEquals(0,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
        }
    }
    private static JsonObject item(String id,String name) { JsonObject item=new JsonObject(); item.addProperty("Id",id); item.addProperty("Name",name); item.addProperty("Type","Video"); return item; }
    private static JsonObject page(int total,JsonObject... items) { JsonObject page=new JsonObject(); page.addProperty("TotalRecordCount",total); JsonArray array=new JsonArray(); for(JsonObject item:items) array.add(item); page.add("Items",array); return page; }
    private static JsonObject season(String id,int number,String date) { JsonObject item=item(id,"Season "+number); item.addProperty("Type","Season"); item.addProperty("SeriesId","series"); item.addProperty("IndexNumber",number); item.addProperty("PremiereDate",date); return item; }
    private static JsonObject episode(String id,int season,String filename) { JsonObject item=item(id,"Episode"); item.addProperty("Type","Episode"); item.addProperty("SeriesId","series"); item.addProperty("SeasonId","season"+season); item.addProperty("ParentIndexNumber",season); item.addProperty("IndexNumber",1); JsonArray sources=new JsonArray(); JsonObject source=new JsonObject(); source.addProperty("Id","source-"+id); source.addProperty("Path","/media/"+filename); sources.add(source); item.add("MediaSources",sources); return item; }
}
