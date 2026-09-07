package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.persistence.MikanListCacheRepository;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the real MikanService list path separately from the HTTP/browser
 * fixture.  The 30 hot samples are complete service calls, including the
 * response copy and optional score-cache read, not just a cache map lookup.
 */
class W6HotListPerformanceTest {
    private static final int HOT_SAMPLE_COUNT = 30;
    private static final long HOT_P95_TARGET_MILLIS = 200;

    private Path configRoot;
    private ConfigSnapshot originalConfig;
    private List<ani.rss.entity.Ani> originalSubscriptions;
    private String cacheKey;

    @BeforeEach
    void setUp() throws Exception {
        configRoot = Path.of("target", "w6-hot-list-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(configRoot);
        originalConfig = new ConfigSnapshot(ConfigUtil.snapshot(), System.getProperty("CONFIG"));
        originalSubscriptions = new ArrayList<>(AniUtil.ANI_LIST);
        System.setProperty("CONFIG", configRoot.toString());
        DatabaseManager.close();
        AniUtil.ANI_LIST.clear();
    }

    @AfterEach
    void tearDown() {
        if (cacheKey != null) {
            CacheUtils.remove(cacheKey);
        }
        DatabaseManager.close();
        AniUtil.ANI_LIST.clear();
        AniUtil.ANI_LIST.addAll(originalSubscriptions);
        ConfigUtil.sync(originalConfig.config());
        if (originalConfig.configPath() == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfig.configPath());
        }
    }

    @Test
    void recordsColdInProcessHotAndRestartPersistentListSamples() throws Exception {
        Mikan.Season season = new Mikan.Season()
                .setYear(2026)
                .setSeason("summer")
                .setSeasonLabel("2026 summer");
        cacheKey = MikanService.listCacheKey("", season);
        CacheUtils.remove(cacheKey);

        AtomicInteger upstreamLoads = new AtomicInteger();
        MikanListCacheRepository repository = new MikanListCacheRepository();
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "");
        MikanService service = new MikanService(scores, (text, requestedSeason) -> {
            upstreamLoads.incrementAndGet();
            return fixture();
        }, repository);
        List<Double> hotDurations = new ArrayList<>();
        double coldDuration;
        double restartDuration;
        try {
            long started = System.nanoTime();
            Mikan cold = service.list("", season);
            coldDuration = elapsedMillis(started);
            assertEquals("W6 hot-list fixture", cold.getWeeks().get(0).getItems().get(0).getTitle());

            for (int index = 0; index < HOT_SAMPLE_COUNT; index++) {
                long sampleStarted = System.nanoTime();
                Mikan hot = service.list("", season);
                hotDurations.add(elapsedMillis(sampleStarted));
                assertEquals("W6 hot-list fixture", hot.getWeeks().get(0).getItems().get(0).getTitle());
            }
        } finally {
            service.stopStaleRefreshExecutor();
            scores.stopWarmupExecutors();
        }

        assertEquals(1, upstreamLoads.get(), "in-process hot calls must reuse the list snapshot");

        DatabaseManager.reopen();
        CacheUtils.remove(cacheKey);
        PublicScoreService restartedScores = new PublicScoreService(id -> null, url -> "");
        MikanService restarted = new MikanService(restartedScores, (text, requestedSeason) -> {
            throw new AssertionError("restart should use the durable list snapshot");
        }, new MikanListCacheRepository());
        try {
            long started = System.nanoTime();
            Mikan restored = restarted.list("", season);
            restartDuration = elapsedMillis(started);
            assertEquals("W6 hot-list fixture", restored.getWeeks().get(0).getItems().get(0).getTitle());
        } finally {
            restarted.stopStaleRefreshExecutor();
            restartedScores.stopWarmupExecutors();
        }

        double p95 = percentile95(hotDurations);
        assertEquals(HOT_SAMPLE_COUNT, hotDurations.size());
        assertTrue(p95 <= HOT_P95_TARGET_MILLIS,
                () -> "in-process hot list service p95 exceeded target: " + p95 + " ms");
        writeReport(coldDuration, hotDurations, restartDuration, upstreamLoads.get(), p95);
    }

    private void writeReport(double coldDuration, List<Double> hotDurations,
                             double restartDuration, int upstreamLoads, double p95) throws Exception {
        String runId = "w6-hot-list-" + Instant.now().toString().replace(':', '-') + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String configuredOutput = System.getProperty("fork.w6.hot.output");
        Path output = configuredOutput == null || configuredOutput.isBlank()
                ? Path.of("target", "w6-performance-data", runId + ".json")
                : Path.of(configuredOutput).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("runId", runId);
        report.put("commit", gitOutput("rev-parse", "HEAD"));
        report.put("dirty", trackedWorkingTreeDirty());
        report.put("buildIdentity", Path.of("target", "classes")
                .toAbsolutePath().normalize().toString());
        report.put("measurementKind", "service-performance");
        report.put("systemUnderTest", List.of(
                "MikanService", "MikanListCacheRepository", "PublicScoreService"));
        report.put("stubbedBoundaries", List.of("MikanListLoader", "public score loader"));
        report.put("scenario", "mikan-list-cold-in-process-hot-restart-persistent");
        report.put("clockMode", "real");
        report.put("environment", Map.of(
                "java", System.getProperty("java.version"),
                "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"),
                "configRoot", configRoot.toString(),
                "sampleCount", HOT_SAMPLE_COUNT));
        report.put("fixture", Map.of(
                "season", "2026 summer",
                "fixtureHash", sha256(GsonStatic.toJson(fixture())),
                "upstreamListLoads", upstreamLoads));
        report.put("samples", List.of(
                Map.of("stage", "cold", "serviceDurationMs", coldDuration),
                Map.of("stage", "in-process-hot", "serviceDurationMs", hotDurations,
                        "sampleCount", hotDurations.size(), "p95ServiceDurationMs", p95),
                Map.of("stage", "restart-persistent", "serviceDurationMs", restartDuration)));
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("mikanListLoaderCalls", upstreamLoads);
        counters.put("hotSampleCount", hotDurations.size());
        counters.put("hotP95TargetMs", HOT_P95_TARGET_MILLIS);
        counters.put("databaseQueries", null);
        counters.put("lockWaitMs", null);
        counters.put("httpRequests", null);
        report.put("counters", counters);
        report.put("assertions", List.of(
                "cold service call loads one synthetic Mikan snapshot",
                "30 complete in-process hot list service calls reuse the snapshot",
                "a cache-memory miss after reopen restores the same snapshot from SQLite",
                "hot p95 is compared with the existing 200ms target without changing the threshold"));
        report.put("limitations", List.of(
                "serviceDurationMs uses monotonic nanosecond timing converted to decimal milliseconds and is not end-to-end HTTP time; it includes the real service response copy and score-cache read",
                "Mikan list transport and public score loader are synthetic; no external account or production data is used",
                "database query count and lock wait are uninstrumented (null), not inferred from the 30 samples"));
        Files.writeString(output, reportJson(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("W6_HOT_LIST_REPORT=" + output);
    }

    private static Mikan fixture() {
        return new Mikan()
                .setSeasons(List.of())
                .setWeeks(List.of(new Mikan.Week()
                        .setWeekLabel("星期六")
                        .setItems(List.of(new MikanInfo()
                                .setUrl("https://mikanani.me/Home/Bangumi/880001")
                                .setTitle("W6 hot-list fixture")
                                .setScore(null)
                                .setExists(false)))))
                .setTotalItem(1);
    }

    private static double elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private static double percentile95(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1);
        return sorted.get(index);
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static String reportJson(Object report) {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(report);
    }

    private static String gitOutput(String... arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return value;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static boolean trackedWorkingTreeDirty() {
        return !gitOutput("status", "--porcelain", "--untracked-files=no").isBlank();
    }

    private record ConfigSnapshot(ani.rss.entity.Config config, String configPath) {
    }
}
