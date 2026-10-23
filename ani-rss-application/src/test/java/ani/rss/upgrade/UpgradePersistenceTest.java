package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.*;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradePersistenceTest {
    @TempDir Path temporary;
    private String previousConfig;
    @BeforeEach void configure() { previousConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",temporary.toString()); DatabaseManager.close(); }
    @AfterEach void close() { DatabaseManager.close(); if(previousConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previousConfig); }
    @Test void legacyMigrationPreservesIndependentOptionsAndExplicitOff() {
        for(boolean delete:List.of(false,true)) for(boolean standby:List.of(false,true)) for(boolean coexist:List.of(false,true)) {
            Config c=new Config().setDelete(delete).setStandbyRss(standby).setCoexist(coexist);
            UpgradeModeService.normalize(c);
            assertEquals(delete&&standby&&!coexist?UpgradeMode.LEGACY:UpgradeMode.OFF,c.getUpgradeMode());
            assertEquals(standby,c.getStandbyRss()); assertEquals(delete,c.getDelete());
            c.setUpgradeMode(UpgradeMode.AUTOMATIC); UpgradeModeService.normalize(c); assertEquals(UpgradeMode.AUTOMATIC,c.getUpgradeMode());
        }
    }
    @Test void durableRevisionRejectsStaleModeAndConfigurationWrites() {
        Config runtime=ConfigUtil.copy(ConfigUtil.CONFIG); UpgradeModeService.normalize(runtime);
        ConfigStore store=new ConfigStore(runtime,()->temporary.resolve("config.json"),UpgradeModeService::normalize);
        Config old=store.snapshot(),next=store.snapshot(); next.setUpgradeMode(UpgradeMode.AUTOMATIC); store.commit(next);
        assertEquals(UpgradeMode.AUTOMATIC,store.snapshot().getUpgradeMode());
        assertThrows(ani.rss.exception.ApiProblemException.class,()->store.commit(old));
        ConfigStore reloaded=new ConfigStore(ConfigUtil.copy(ConfigUtil.CONFIG),()->temporary.resolve("config.json"),UpgradeModeService::normalize);
        reloaded.load(ConfigUtil.copy(ConfigUtil.CONFIG)); assertEquals(store.snapshot().getUpgradeModeRevision(),reloaded.snapshot().getUpgradeModeRevision());
    }
    @Test void schemaAndCandidateIdentitySurviveDatabaseReopen() {
        UpgradeRepository r=new UpgradeRepository(); Release release=new Release(); release.setId("release1"); release.setTargetId("target1");
        r.save("upgrade_releases",release.getId(),release.getTargetId(),"target1:hash","ANALYZING",1,release);
        DatabaseManager.reopen(); assertEquals("release1",r.identity("upgrade_releases","target1:hash",Release.class).orElseThrow().getId());
        assertThrows(IllegalStateException.class,()->r.save("upgrade_releases","release2","target1","target1:hash","ANALYZING",1,release));
        assertTrue(DatabaseManager.integrityCheck());
        DatabaseManager.withConnection(c->{ try(var s=c.createStatement();var result=s.executeQuery("SELECT MAX(version) FROM schema_migrations")) { assertTrue(result.next()); assertEquals(12,result.getInt(1)); } return null; });
    }
    @Test void rejectsInvalidAndOversizedQueries() {
        UpgradeRepository r=new UpgradeRepository(); assertThrows(IllegalArgumentException.class,()->r.list("upgrade_jobs",null,null,0,201,Job.class));
        assertThrows(IllegalArgumentException.class,()->r.list("upgrade_jobs; DROP TABLE upgrade_jobs",null,null,0,50,Job.class));
        assertThrows(IllegalArgumentException.class,()->r.save("upgrade_events","bad","",null,"",1,Map.of("text","x".repeat(1048576))));
    }
}
