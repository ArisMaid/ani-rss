package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in maintenance for the local monitor-only acceptance profile; never a default test. */
@EnabledIfSystemProperty(named="upgrade.local.reanalyze",matches=".+")
class UpgradeLocalReanalysisTest {
    @Test void refreshEveryStoredTargetAndItsIndependentRss() throws Exception {
        Path profile=Path.of(System.getProperty("upgrade.local.reanalyze")).toAbsolutePath().normalize();
        Path project=Path.of("").toAbsolutePath().normalize().getParent();
        assertEquals(project.resolve("output/local-upgrade-test-config"),profile);
        String previous=System.getProperty("CONFIG"); Config original=ConfigUtil.snapshot();
        System.setProperty("CONFIG",profile.toString()); DatabaseManager.close();
        try(var channel=java.nio.channels.FileChannel.open(profile.resolve("instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE); var lock=channel.tryLock()) {
            assertNotNull(lock,"Stop the application using this profile before local maintenance"); ConfigUtil.load();
            var repository=new UpgradeRepository(); var analysis=new ReleaseAnalysisService(); var provider=new ReleaseProvider(repository,analysis);
            List<Target> targets=repository.all("upgrade_targets",null,null,Target.class);
            assertTrue(targets.stream().allMatch(t->UpgradeCampaignService.monitorOnly(t.getLibraryId())));
            assertTrue(repository.all("upgrade_jobs",null,null,Job.class).stream().noneMatch(j->"UPGRADE".equals(j.getKind())));
            var paths=new LibraryPathMappingService(); var space=new SpaceReservationService(); var commits=new UpgradeCommitService(repository,paths,space);
            var scheduler=new UpgradeScheduler(repository,new UpgradeCampaignService(repository,new QualityPolicyEngine()),null,null,provider,null,new QualityPolicyEngine(),null,commits,space,null,null,null,null);
            int completed=0;
            boolean fetchRss=!"false".equals(System.getProperty("upgrade.local.fetchRss"));
            for(Target target:targets) { if(fetchRss) provider.rss(target); scheduler.reanalyzeTarget(target); if(++completed%20==0) System.out.println("REANALYZED "+completed+"/"+targets.size()); }
            List<Release> releases=repository.all("upgrade_releases",null,null,Release.class);
            assertEquals(0,releases.stream().filter(r->"RSS".equals(r.getSource())&&!"MERGED".equals(r.getState())&&r.getInfoHash()==null&&r.getTorrentUrl()!=null&&r.getTorrentUrl().matches(".*/[a-fA-F0-9]{40}\\.torrent.*")).count());
            Path report=project.resolve("output/automation-acceptance-20261025/offline-reanalysis-"+System.currentTimeMillis()+".json");
            Files.writeString(report,GsonStatic.toJson(Map.of("passed",true,"targets",targets.size(),"releaseRecords",releases.size(),"monitorOnly",true,"rssCursors",repository.all("upgrade_scan_cursors",null,null,Cursor.class).stream().filter(c->c.getId().startsWith("rss:")).count())));
        } finally {
            DatabaseManager.close(); cn.hutool.core.bean.BeanUtil.copyProperties(original,ConfigUtil.CONFIG);
            if(previous==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previous);
        }
    }
}
