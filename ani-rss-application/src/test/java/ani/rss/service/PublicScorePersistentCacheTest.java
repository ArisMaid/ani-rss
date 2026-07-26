package ani.rss.service;

import ani.rss.entity.MikanInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.PublicScoreCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PublicScorePersistentCacheTest {
    @TempDir
    Path tempDir;

    private PublicScoreCacheRepository repository;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new PublicScoreCacheRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void reusesDurablePublicScoresAfterTheDatabaseConnectionIsReopened() {
        String mikanId = String.valueOf(System.nanoTime());
        String bgmId = String.valueOf(System.nanoTime() + 1);
        long expiresAt = System.currentTimeMillis() + 60_000;
        repository.saveMikanMapping(mikanId, bgmId, expiresAt);
        repository.saveBgmScore(bgmId, 8.9, expiresAt);
        DatabaseManager.reopen();

        AtomicInteger mappingCalls = new AtomicInteger();
        AtomicInteger scoreCalls = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    scoreCalls.incrementAndGet();
                    throw new AssertionError("durable score cache should avoid an upstream request");
                },
                url -> {
                    mappingCalls.incrementAndGet();
                    throw new AssertionError("durable mapping cache should avoid an upstream request");
                },
                repository
        );
        try {
            PublicScoreService.MikanScoreLookup result = service.getCachedMikanScoreLookupAndWarm(List.of(
                    new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + mikanId)
            ));

            assertEquals(bgmId, result.scores().get(mikanId).getBgmId());
            assertEquals(8.9, result.scores().get(mikanId).getScore());
            assertFalse(result.retryableMikanIds().contains(mikanId));
            assertEquals(0, mappingCalls.get());
            assertEquals(0, scoreCalls.get());
        } finally {
            service.stopWarmupExecutors();
        }
    }
}
