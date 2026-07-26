package ani.rss.service;

import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.MikanListCacheRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
        CacheUtils.remove(MikanService.listCacheKey("", season));
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

    @Test
    void servesAnExpiredSeasonSnapshotWithoutWaitingForItsSingleFlightRefresh() throws Exception {
        Mikan.Season season = new Mikan.Season()
                .setYear(2037)
                .setSeason("summer")
                .setSeasonLabel("2037 summer");
        MikanListCacheRepository repository = new MikanListCacheRepository();
        String cacheKey = MikanService.listCacheKey("", season);
        repository.save(cacheKey, GsonStatic.toJson(snapshot("stale season snapshot")),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
        expire(cacheKey);
        CacheUtils.remove(cacheKey);

        AtomicInteger upstreamLoads = new AtomicInteger();
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "");
        MikanService service = new MikanService(scores, (text, requestedSeason) -> {
            upstreamLoads.incrementAndGet();
            refreshStarted.countDown();
            try {
                if (!releaseRefresh.await(1, TimeUnit.SECONDS)) {
                    throw new AssertionError("background refresh was not released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return snapshot("fresh season snapshot");
        }, repository);
        try {
            assertEquals("stale season snapshot", service.list("", season)
                    .getWeeks().get(0).getItems().get(0).getTitle());
            assertTrue(refreshStarted.await(1, TimeUnit.SECONDS));

            // A second UI request returns the known snapshot instead of
            // queueing a duplicate Mikan request while the refresh is running.
            assertEquals("stale season snapshot", service.list("", season)
                    .getWeeks().get(0).getItems().get(0).getTitle());
            assertEquals(1, upstreamLoads.get());

            releaseRefresh.countDown();
            awaitTitle(service, season, "fresh season snapshot");
            assertEquals(1, upstreamLoads.get());
        } finally {
            releaseRefresh.countDown();
            service.stopStaleRefreshExecutor();
            scores.stopWarmupExecutors();
        }
    }

    @Test
    void keepsAnExpiredSeasonSnapshotWhenBackgroundRefreshFails() throws Exception {
        Mikan.Season season = new Mikan.Season()
                .setYear(2037)
                .setSeason("fall")
                .setSeasonLabel("2037 fall");
        MikanListCacheRepository repository = new MikanListCacheRepository();
        String cacheKey = MikanService.listCacheKey("", season);
        repository.save(cacheKey, GsonStatic.toJson(snapshot("stale season snapshot")),
                System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(1));
        expire(cacheKey);
        CacheUtils.remove(cacheKey);

        CountDownLatch refreshAttempted = new CountDownLatch(1);
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "");
        MikanService service = new MikanService(scores, (text, requestedSeason) -> {
            refreshAttempted.countDown();
            throw new IllegalStateException("Mikan is temporarily unavailable");
        }, repository);
        try {
            assertEquals("stale season snapshot", service.list("", season)
                    .getWeeks().get(0).getItems().get(0).getTitle());
            assertTrue(refreshAttempted.await(1, TimeUnit.SECONDS));
            assertEquals("stale season snapshot", repository.findLatest(cacheKey)
                    .map(snapshot -> GsonStatic.GSON.fromJson(snapshot.snapshotJson(), Mikan.class))
                    .orElseThrow()
                    .getWeeks().get(0).getItems().get(0).getTitle());
        } finally {
            service.stopStaleRefreshExecutor();
            scores.stopWarmupExecutors();
        }
    }

    private static void expire(String cacheKey) {
        DatabaseManager.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "UPDATE mikan_list_cache SET expires_at = ? WHERE cache_key = ?")) {
                statement.setLong(1, System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(1));
                statement.setString(2, cacheKey);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private static void awaitTitle(MikanService service, Mikan.Season season, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            String actual = service.list("", season).getWeeks().get(0).getItems().get(0).getTitle();
            if (expected.equals(actual)) {
                return;
            }
            Thread.sleep(10);
        }
        fail("timed out waiting for refreshed Mikan season snapshot");
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
