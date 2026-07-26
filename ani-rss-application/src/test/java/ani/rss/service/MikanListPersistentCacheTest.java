package ani.rss.service;

import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import ani.rss.commons.CacheUtils;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.MikanListCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MikanListPersistentCacheTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void reusesARecentSeasonSnapshotAfterTheServiceAndDatabaseAreReopened() {
        AtomicInteger upstreamLoads = new AtomicInteger();
        Mikan.Season season = new Mikan.Season()
                .setYear(2026)
                .setSeason("summer")
                .setSeasonLabel("2026 summer");
        MikanListCacheRepository repository = new MikanListCacheRepository();
        PublicScoreService firstScores = new PublicScoreService(id -> null, url -> "");
        MikanService first = new MikanService(firstScores, (text, requestedSeason) -> {
            upstreamLoads.incrementAndGet();
            return snapshot("first season snapshot");
        }, repository);
        try {
            assertEquals("first season snapshot", first.list("", season)
                    .getWeeks().get(0).getItems().get(0).getTitle());
        } finally {
            firstScores.stopWarmupExecutors();
        }

        DatabaseManager.reopen();
        CacheUtils.remove(MikanService.listCacheKey("", season));
        PublicScoreService secondScores = new PublicScoreService(id -> null, url -> "");
        MikanService restarted = new MikanService(secondScores, (text, requestedSeason) -> {
            upstreamLoads.incrementAndGet();
            throw new AssertionError("durable season snapshot should avoid a second Mikan request");
        }, new MikanListCacheRepository());
        try {
            Mikan restored = restarted.list("", season);

            assertEquals(1, upstreamLoads.get());
            assertEquals("first season snapshot", restored.getWeeks().get(0).getItems().get(0).getTitle());
        } finally {
            secondScores.stopWarmupExecutors();
        }
    }

    private static Mikan snapshot(String title) {
        return new Mikan()
                .setSeasons(List.of())
                .setWeeks(List.of(new Mikan.Week()
                        .setWeekLabel("Saturday")
                        .setItems(List.of(new MikanInfo()
                                .setUrl("https://mikanani.me/Home/Bangumi/12345")
                                .setTitle(title)
                                .setScore(0.0)
                                .setExists(false)))))
                .setTotalItem(1);
    }
}
