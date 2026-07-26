package ani.rss.service;

import ani.rss.entity.MikanInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.PublicScoreCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    @Test
    void readsAnEntireCachedSeasonWithOneMappingAndOneScoreQuery() {
        long seed = Math.abs(System.nanoTime());
        List<String> mikanIds = List.of(
                String.valueOf(seed + 1), String.valueOf(seed + 2), String.valueOf(seed + 3));
        List<String> bgmIds = List.of(
                String.valueOf(seed + 11), String.valueOf(seed + 12), String.valueOf(seed + 13));
        long expiresAt = System.currentTimeMillis() + 60_000;
        for (int index = 0; index < mikanIds.size(); index++) {
            repository.saveMikanMapping(mikanIds.get(index), bgmIds.get(index), expiresAt);
            repository.saveBgmScore(bgmIds.get(index), 8.0 + index / 10.0, expiresAt);
        }
        DatabaseManager.reopen();

        CountingRepository countingRepository = new CountingRepository();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    throw new AssertionError("durable score cache should avoid an upstream request");
                },
                url -> {
                    throw new AssertionError("durable mapping cache should avoid an upstream request");
                },
                countingRepository
        );
        try {
            PublicScoreService.MikanScoreLookup result = service.getCachedMikanScoreLookupAndWarm(
                    mikanIds.stream()
                            .map(id -> new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + id))
                            .toList());

            assertEquals(3, result.scores().size());
            assertEquals(1, countingRepository.mappingBatchReads.get());
            assertEquals(1, countingRepository.scoreBatchReads.get());
            assertEquals(0, countingRepository.singleMappingReads.get());
            assertEquals(0, countingRepository.singleScoreReads.get());
        } finally {
            service.stopWarmupExecutors();
        }
    }

    private static final class CountingRepository extends PublicScoreCacheRepository {
        private final AtomicInteger singleMappingReads = new AtomicInteger();
        private final AtomicInteger singleScoreReads = new AtomicInteger();
        private final AtomicInteger mappingBatchReads = new AtomicInteger();
        private final AtomicInteger scoreBatchReads = new AtomicInteger();

        @Override
        public Optional<MikanMapping> findMikanMapping(String mikanId, long now) {
            singleMappingReads.incrementAndGet();
            return super.findMikanMapping(mikanId, now);
        }

        @Override
        public Optional<BgmScore> findBgmScore(String bgmId, long now) {
            singleScoreReads.incrementAndGet();
            return super.findBgmScore(bgmId, now);
        }

        @Override
        public Map<String, MikanMapping> findMikanMappings(Collection<String> mikanIds, long now) {
            mappingBatchReads.incrementAndGet();
            return super.findMikanMappings(mikanIds, now);
        }

        @Override
        public Map<String, BgmScore> findBgmScores(Collection<String> bgmIds, long now) {
            scoreBatchReads.incrementAndGet();
            return super.findBgmScores(bgmIds, now);
        }
    }
}
