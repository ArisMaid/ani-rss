package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.entity.Config;
import ani.rss.entity.Mikan;
import ani.rss.entity.MikanBgm;
import ani.rss.entity.MikanInfo;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MikanServiceTest {
    private final Config original = ConfigUtil.copy(ConfigUtil.CONFIG);

    @AfterEach
    void restoreConfiguration() {
        BeanUtil.copyProperties(original, ConfigUtil.CONFIG);
    }

    @Test
    void appliesScoresToEverySeasonResponseAndResetsMissingScoresToZero() {
        Mikan spring = season("101", "102");
        MikanService.applyScores(
                spring,
                Map.of(
                        "101", new MikanBgm("101", "201", 7.4),
                        "102", new MikanBgm("102", "202", 8.5)
                ),
                Set.of("202")
        );

        List<MikanInfo> springItems = spring.getWeeks().get(0).getItems();
        assertEquals("102", PublicScoreService.extractMikanId(springItems.get(0).getUrl()));
        assertEquals(8.5, springItems.get(0).getScore());
        assertTrue(springItems.get(0).getExists());
        assertEquals(7.4, springItems.get(1).getScore());

        Mikan summer = season("301", "302");
        MikanService.applyScores(
                summer,
                Map.of("301", new MikanBgm("301", "401", 9.1)),
                Set.of()
        );

        List<MikanInfo> summerItems = summer.getWeeks().get(0).getItems();
        assertEquals(9.1, summerItems.get(0).getScore());
        assertEquals(0.0, summerItems.get(1).getScore());
    }

    @Test
    void reportsAnUpstreamFailureInsteadOfReturningAnIndistinguishableEmptyList() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            ConfigUtil.CONFIG.setMikanHost("http://127.0.0.1:" + server.getAddress().getPort())
                    .setProxy(false);

            assertThrows(UpstreamServiceException.class,
                    () -> new MikanService().search("", new Mikan.Season()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resolvesRelativeAndAbsoluteMikanAssetsWithoutConcatenatingHosts() {
        ConfigUtil.CONFIG.setMikanHost("https://mikan.example/proxy").setProxy(false);

        assertEquals("https://mikan.example/proxy/images/cover.webp",
                MikanService.resolveMikanUrl("images/cover.webp"));
        assertEquals("https://mikan.example/images/cover.webp",
                MikanService.resolveMikanUrl("/images/cover.webp"));
        assertEquals("https://cdn.example/cover.webp",
                MikanService.resolveMikanUrl("https://cdn.example/cover.webp"));
        assertEquals("https://cdn.example/cover.webp",
                MikanService.resolveMikanUrl("//cdn.example/cover.webp"));
        assertEquals("", MikanService.resolveMikanUrl("javascript:alert(1)"));
    }

    @Test
    void reusesARecentListSnapshotWithoutLeakingMutableResponseState() {
        AtomicInteger upstreamRequests = new AtomicInteger();
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "");
        MikanService service = new MikanService(scores, (text, season) -> {
            upstreamRequests.incrementAndGet();
            return new Mikan()
                    .setSeasons(List.of())
                    .setWeeks(List.of(new Mikan.Week()
                            .setWeekLabel("Search")
                            .setItems(List.of(new MikanInfo()
                                    .setUrl("https://mikanani.me/Home/Bangumi/" + text)
                                    .setTitle("原始标题")
                                    .setScore(0.0)
                                    .setExists(false)))))
                    .setTotalItem(1);
        });
        String uniqueQuery = String.valueOf(System.nanoTime());

        Mikan first = service.list(uniqueQuery, new Mikan.Season());
        first.getWeeks().get(0).getItems().get(0).setTitle("被调用方修改");
        Mikan second = service.list(uniqueQuery, new Mikan.Season());

        assertEquals(1, upstreamRequests.get());
        assertEquals("原始标题", second.getWeeks().get(0).getItems().get(0).getTitle());
    }

    @Test
    void prefetchesOneAdjacentSeasonWithoutRecursing() throws Exception {
        int year = 100_000 + (int) Math.floorMod(System.nanoTime(), 100_000L);
        Mikan.Season selected = new Mikan.Season()
                .setYear(year)
                .setSeason("summer")
                .setSeasonLabel(year + " summer")
                .setSelect(true);
        Mikan.Season adjacent = new Mikan.Season()
                .setYear(year)
                .setSeason("spring")
                .setSeasonLabel(year + " spring")
                .setSelect(false);
        String defaultCacheKey = MikanService.listCacheKey("", new Mikan.Season());
        String adjacentCacheKey = MikanService.listCacheKey("", adjacent);
        CacheUtils.remove(defaultCacheKey);
        CacheUtils.remove(adjacentCacheKey);

        AtomicInteger upstreamLoads = new AtomicInteger();
        CountDownLatch adjacentLoaded = new CountDownLatch(1);
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "");
        MikanService service = new MikanService(scores, (text, requestedSeason) -> {
            upstreamLoads.incrementAndGet();
            boolean isAdjacent = adjacent.getYear().equals(requestedSeason.getYear())
                    && adjacent.getSeason().equals(requestedSeason.getSeason());
            if (isAdjacent) {
                adjacentLoaded.countDown();
                // The background prefetch itself must not discover another
                // neighbour and continue crawling the menu.
                return mikanList("prefetched adjacent season", List.of(adjacent));
            }
            return mikanList("foreground season", List.of(selected, adjacent));
        });
        try {
            Mikan foreground = service.list("", new Mikan.Season());
            assertEquals("foreground season", foreground.getWeeks().get(0).getItems().get(0).getTitle());
            assertTrue(adjacentLoaded.await(1, TimeUnit.SECONDS));

            Mikan cachedAdjacent = service.list("", adjacent);
            assertEquals("prefetched adjacent season",
                    cachedAdjacent.getWeeks().get(0).getItems().get(0).getTitle());
            assertEquals(2, upstreamLoads.get(), "the prefetch must not recursively crawl more seasons");
        } finally {
            service.stopStaleRefreshExecutor();
            scores.stopWarmupExecutors();
            CacheUtils.remove(defaultCacheKey);
            CacheUtils.remove(adjacentCacheKey);
        }
    }

    @Test
    void readsCachedScoresAndStartsWarmupInOneSeasonPass() {
        String mikanId = String.valueOf(System.nanoTime());
        CountingScoreService scores = new CountingScoreService(mikanId);
        MikanService service = new MikanService(scores, (text, season) -> {
            MikanInfo item = new MikanInfo()
                    .setUrl("https://mikanani.me/Home/Bangumi/" + mikanId)
                    .setTitle("Single pass")
                    .setScore(0.0)
                    .setExists(false);
            return new Mikan()
                    .setSeasons(List.of())
                    .setWeeks(List.of(new Mikan.Week()
                            .setWeekLabel("Search")
                            .setItems(new ArrayList<>(List.of(item)))))
                    .setTotalItem(1);
        });
        try {
            Mikan result = service.list("single-pass-" + mikanId, new Mikan.Season());

            assertEquals(1, scores.combinedLookupCalls.get());
            assertEquals(MikanService.MAX_BACKGROUND_SCORE_WARMUPS_PER_LIST,
                    scores.lastColdWarmupLimit.get());
            assertEquals(0, scores.legacyCachedLookupCalls.get());
            assertEquals(0, scores.legacyWarmupCalls.get());
            assertEquals(8.6, result.getWeeks().get(0).getItems().get(0).getScore());
        } finally {
            scores.stopWarmupExecutors();
        }
    }

    private static Mikan season(String first, String second) {
        List<MikanInfo> items = new ArrayList<>(List.of(
                new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + first),
                new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + second)
        ));
        return new Mikan().setWeeks(List.of(new Mikan.Week().setItems(items)));
    }

    private static Mikan mikanList(String title, List<Mikan.Season> seasons) {
        return new Mikan()
                .setSeasons(seasons)
                .setWeeks(List.of(new Mikan.Week()
                        .setWeekLabel("Saturday")
                        .setItems(List.of(new MikanInfo()
                                .setUrl("https://mikanani.me/Home/Bangumi/not-a-number")
                                .setTitle(title)
                                .setScore(0.0)
                                .setExists(false)))))
                .setTotalItem(1);
    }

    private static final class CountingScoreService extends PublicScoreService {
        private final String mikanId;
        private final AtomicInteger combinedLookupCalls = new AtomicInteger();
        private final AtomicInteger lastColdWarmupLimit = new AtomicInteger(-1);
        private final AtomicInteger legacyCachedLookupCalls = new AtomicInteger();
        private final AtomicInteger legacyWarmupCalls = new AtomicInteger();

        private CountingScoreService(String mikanId) {
            super(id -> null, url -> "");
            this.mikanId = mikanId;
        }

        @Override
        MikanScoreLookup getCachedMikanScoreLookupAndWarm(
                Collection<MikanInfo> mikanInfos, int maxColdWarmups) {
            combinedLookupCalls.incrementAndGet();
            lastColdWarmupLimit.set(maxColdWarmups);
            return new MikanScoreLookup(
                    Map.of(mikanId, new MikanBgm(mikanId, "123", 8.6)), Set.of());
        }

        @Override
        public Map<String, MikanBgm> getCachedMikanScores(Collection<MikanInfo> mikanInfos) {
            legacyCachedLookupCalls.incrementAndGet();
            return Map.of();
        }

        @Override
        public void warmMikanScores(Collection<MikanInfo> mikanInfos) {
            legacyWarmupCalls.incrementAndGet();
        }
    }
}
