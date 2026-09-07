package ani.rss.service;

import ani.rss.entity.MikanInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.PublicScoreCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicScorePersistentCacheTest {
    Path tempDir;
    private String originalConfigPath;

    private PublicScoreCacheRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        originalConfigPath = System.getProperty("CONFIG");
        tempDir = Path.of("target", "fork-score-cache-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(tempDir.resolve("config"));
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new PublicScoreCacheRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        if (originalConfigPath == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfigPath);
        }
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

    @Test
    void expiresASevenHourOldScoreEvenWhenItsStoredExpiryIsStillInTheFuture() {
        String bgmId = String.valueOf(System.nanoTime());
        repository.saveBgmScore(bgmId, 8.4, System.currentTimeMillis() + TimeUnit.DAYS.toMillis(2));
        DatabaseManager.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE public_bgm_score_cache SET updated_at = ?, expires_at = ? WHERE bgm_id = ?")) {
                statement.setLong(1, System.currentTimeMillis() - TimeUnit.HOURS.toMillis(7));
                statement.setLong(2, System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2));
                statement.setString(3, bgmId);
                statement.executeUpdate();
            }
            return null;
        });

        assertTrue(repository.findBgmScore(bgmId, System.currentTimeMillis()).isEmpty());

        AtomicInteger upstreamCalls = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    upstreamCalls.incrementAndGet();
                    return new ani.rss.entity.BgmInfo()
                            .setRating(new ani.rss.entity.BgmInfo.Rating().setScore(9.1));
                },
                url -> "",
                repository
        );
        try {
            assertEquals(9.1, service.getBgmScores(List.of(bgmId)).get(bgmId));
            assertEquals(1, upstreamCalls.get(), "a restart must not renew an old durable score");
        } finally {
            service.stopWarmupExecutors();
        }
    }

    @Test
    void delayedDurableWriterUsesTheObservedExpiryInsteadOfRenewingAtWriteTime() throws Exception {
        DelayedScoreRepository delayed = new DelayedScoreRepository();
        String bgmId = String.valueOf(System.nanoTime());
        PublicScoreService service = new PublicScoreService(
                id -> new ani.rss.entity.BgmInfo()
                        .setRating(new ani.rss.entity.BgmInfo.Rating().setScore(8.8)),
                url -> "",
                delayed
        );
        try {
            service.getCachedBgmScoresAndWarm(List.of(bgmId));
            assertTrue(delayed.saveStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            delayed.release.countDown();
            assertTrue(delayed.saved.await(2, TimeUnit.SECONDS));
            assertEquals(TimeUnit.HOURS.toMillis(6),
                    delayed.expiresAt.get() - delayed.observedAt.get());
        } finally {
            delayed.release.countDown();
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

    private static final class DelayedScoreRepository extends PublicScoreCacheRepository {
        private final CountDownLatch saveStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch saved = new CountDownLatch(1);
        private final AtomicLong observedAt = new AtomicLong();
        private final AtomicLong expiresAt = new AtomicLong();

        @Override
        public Map<String, BgmScore> findBgmScores(Collection<String> bgmIds, long now) {
            return Map.of();
        }

        @Override
        public void saveBgmScore(String bgmId, double score, long expiresAt, long observedAt) {
            this.observedAt.set(observedAt);
            this.expiresAt.set(expiresAt);
            saveStarted.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                saved.countDown();
            }
        }
    }
}
