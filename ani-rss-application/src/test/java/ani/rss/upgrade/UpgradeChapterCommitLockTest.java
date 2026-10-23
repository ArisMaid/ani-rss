package ani.rss.upgrade;

import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeChapterCommitLockTest {
    @TempDir Path temporary;
    private String previousConfig;
    private UpgradeRepository repository;
    private final ChapterCommitLockService locks=new ChapterCommitLockService();
    private Target target;
    @BeforeEach void configure() {
        previousConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",temporary.toString()); DatabaseManager.close();
        repository=new UpgradeRepository(); target=new Target(); target.setSubjectId("100"); target.setLibraryId("lib");
    }
    @AfterEach void close() { DatabaseManager.close(); if(previousConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previousConfig); }
    private Job job(String id,JobState state) {
        Job job=new Job(); job.setId(id); job.setState(state); repository.save("upgrade_jobs",id,"target",null,state.name(),1,job); return job;
    }
    private List<JobFile> files(String... episodes) {
        Release release=new Release(); release.setSubjectId("100"); release.getEpisodes().addAll(List.of(episodes));
        JobFile file=new JobFile(); file.setAttributes(release); return List.of(file);
    }
    @Test void restartAndNewChapterMetadataCannotBypassAnOlderNumericClaim() {
        assertTrue(locks.acquire(job("a",JobState.INSTALLING),target,files("1"),System.currentTimeMillis()));
        DatabaseManager.reopen(); target.getChapterIds().put("NORMAL:1","500");
        assertFalse(locks.acquire(job("b",JobState.READY_TO_COMMIT),target,files("1"),System.currentTimeMillis()));
        assertTrue(locks.held("100","lib",target,files("1").get(0).getAttributes()));
        locks.release("a"); assertTrue(locks.acquire(job("b",JobState.READY_TO_COMMIT),target,files("1"),System.currentTimeMillis()));
        assertEquals(2,locks.list("b").size());
    }
    @Test void batchAcquisitionIsAtomicAndOtherLibrariesChaptersAndEditionsDoNotConflict() {
        long now=System.currentTimeMillis(); assertTrue(locks.acquire(job("a",JobState.INSTALLING),target,files("2"),now));
        Job second=job("b",JobState.READY_TO_COMMIT);
        assertFalse(locks.acquire(second,target,files("1","2"),now)); assertTrue(locks.list("b").isEmpty());
        assertTrue(locks.acquire(second,target,files("1"),now));
        Target other=new Target(); other.setSubjectId("100"); other.setLibraryId("other");
        assertTrue(locks.acquire(job("c",JobState.READY_TO_COMMIT),other,files("2"),now));
        List<JobFile> alternate=files("2"); alternate.get(0).getAttributes().setContentEdition("DIRECTORS_CUT");
        assertTrue(locks.acquire(job("d",JobState.READY_TO_COMMIT),target,alternate,now));
    }
    @Test void expiredLeaseDoesNotAuthorizeStealingAnUnfinishedFileCommit() {
        Job first=job("a",JobState.CLEANUP_PENDING); assertTrue(locks.acquire(first,target,files("1"),0));
        Job second=job("b",JobState.READY_TO_COMMIT);
        assertFalse(locks.acquire(second,target,files("1"),System.currentTimeMillis()));
        first.setState(JobState.READY_TO_COMMIT); repository.save("upgrade_jobs","a","target",null,first.getState().name(),1,first);
        assertTrue(locks.acquire(second,target,files("1"),System.currentTimeMillis()));
    }
    @Test void movieHasAStableNonNumericChapterKey() {
        var movie=files("MOVIE"); movie.get(0).getAttributes().setChapterType("MOVIE");
        assertTrue(locks.acquire(job("movie",JobState.INSTALLING),target,movie,System.currentTimeMillis()));
        assertFalse(locks.acquire(job("other",JobState.READY_TO_COMMIT),target,movie,System.currentTimeMillis()));
    }
}
