package ani.rss.task;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.service.TaskService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import ani.rss.commons.GsonStatic;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.entity.Login;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Measures the real RSS worker chain with only the RSS transport and
 * downloader boundary replaced.  The report is deliberately opt-in: normal
 * CI runs keep the evidence under target, while a release audit can provide
 * -Dfork.w6.output=... to retain a raw result in docs/performance-data.
 */
@SpringBootTest(properties = "ani-rss.startup.enabled=false")
class RssApplicationChainPerformanceTest {
    private static final int SUBSCRIPTION_COUNT = 100;
    private static final String ORIGINAL_CONFIG = System.getProperty("CONFIG");
    private static final Path ISOLATED_CONFIG = Path.of("target", "w6-application-config")
            .toAbsolutePath().normalize();

    static {
        // ConfigUtil is process-global and may be initialized while the Spring
        // test context is built.  Keep this chain in a dedicated target tree,
        // never in the user's configured data directory.
        System.setProperty("CONFIG", ISOLATED_CONFIG.toString());
    }

    /** Retained on purpose: JUnit TempDir cleanup would recursively delete fixture data. */
    private Path fixtureRoot;

    private Config originalConfig;
    private DownloaderClient originalClient;

    @BeforeEach
    void setUp() throws Exception {
        fixtureRoot = Path.of("target", "w6-rss-fixture-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(fixtureRoot);
        originalConfig = ConfigUtil.snapshot();
        originalClient = TorrentUtil.client();
        Config config = ConfigUtil.copy(originalConfig)
                .setRss(true)
                .setStandbyRss(false)
                .setDownloadToolType("qBittorrent")
                .setDownloadToolHost("http://fixture-downloader.invalid")
                .setDownloadPathTemplate(fixtureRoot.resolve("downloads/${title}/Season ${season}").toString())
                .setOvaDownloadPathTemplate(fixtureRoot.resolve("ova/${title}").toString())
                .setLogin(new Login().setUsername("w6-user").setPassword("w6-password"));
        ConfigUtil.sync(config);
        TaskService.LOOP.set(true);
    }

    @AfterEach
    void restoreRuntimeState() throws Exception {
        RssTask.resetTestHooks();
        TorrentUtil.resetTestMonotonicClock();
        TaskService.LOOP.set(false);
        if (originalConfig != null) {
            ConfigUtil.sync(originalConfig);
        }
        if (originalClient != null) {
            setTorrentClient(originalClient);
        }
        AniUtil.ANI_LIST.clear();
    }

    @AfterAll
    static void restoreConfigProperty() {
        if (ORIGINAL_CONFIG == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", ORIGINAL_CONFIG);
        }
    }

    @Test
    void measuresOneHundredEnabledSubscriptionsThroughTheRealRssWorker() throws Exception {
        AtomicInteger rssRequests = new AtomicInteger();
        AtomicInteger rssFailures = new AtomicInteger();
        AtomicInteger downloaderConnectCalls = new AtomicInteger();
        AtomicInteger downloaderListCalls = new AtomicInteger();
        AtomicLong virtualSleepMillis = new AtomicLong();
        AtomicLong virtualMonotonicNanos = new AtomicLong();
        Set<String> rssThreads = ConcurrentHashMap.newKeySet();
        Set<String> downloaderThreads = ConcurrentHashMap.newKeySet();
        Map<String, RssTask.Outcome> outcomes = new LinkedHashMap<>();
        HttpServer server = createRssServer(
                rssRequests, rssFailures, rssThreads);
        DownloaderClient client = mock(DownloaderClient.class);
        Config config = ConfigUtil.snapshot();
        when(client.configurationSnapshot()).thenReturn(config);
        when(client.connect(anyBoolean())).thenAnswer((Answer<DownloaderResult<Void>>) invocation -> {
            downloaderConnectCalls.incrementAndGet();
            downloaderThreads.add(Thread.currentThread().getName());
            return DownloaderResult.success(null);
        });
        when(client.torrents()).thenAnswer(invocation -> {
            downloaderListCalls.incrementAndGet();
            downloaderThreads.add(Thread.currentThread().getName());
            return DownloaderResult.success(List.of());
        });
        setTorrentClient(client);

        List<Ani> subscriptions = subscriptions(server.getAddress().getPort());
        AniUtil.commit(subscriptions);
        RssTask.installTestHooks(
                milliseconds -> {
                    virtualSleepMillis.addAndGet(milliseconds);
                    virtualMonotonicNanos.addAndGet(milliseconds * 1_000_000L);
                },
                (id, outcome) -> outcomes.put(id, outcome));
        TorrentUtil.installTestMonotonicClock(virtualMonotonicNanos::get);
        long started = System.nanoTime();
        try {
            RssTask.syncDownload(subscriptions);
        } finally {
            RssTask.resetTestHooks();
            TaskService.LOOP.set(false);
            server.stop(0);
            setTorrentClient(originalClient);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        ListAniSnapshot status = new ListAniSnapshot(RssTask.refreshStatus());

        assertEquals(SUBSCRIPTION_COUNT, rssRequests.get());
        assertEquals(0, rssFailures.get());
        assertEquals(1, downloaderConnectCalls.get());
        assertEquals(10, downloaderListCalls.get(),
                "the shared fake clock must force a fresh snapshot at each five-second boundary");
        assertEquals(SUBSCRIPTION_COUNT, outcomes.size());
        assertTrue(outcomes.values().stream().allMatch(RssTask.Outcome.SUCCESS::equals));
        assertEquals(0, status.failedCount());
        assertTrue(status.finishedAt() > 0);
        assertEquals(SUBSCRIPTION_COUNT * 500L, virtualSleepMillis.get());

        writeReport(
                subscriptions,
                outcomes,
                rssRequests.get(),
                rssFailures.get(),
                downloaderConnectCalls.get(),
                downloaderListCalls.get(),
                virtualSleepMillis.get(),
                elapsedMillis,
                rssThreads,
                downloaderThreads,
                status
        );
    }

    @Test
    void exercisesARealRssAddFixtureThroughTheSameWorkerChain() throws Exception {
        AtomicInteger rssRequests = new AtomicInteger();
        AtomicInteger rssFailures = new AtomicInteger();
        AtomicInteger addCalls = new AtomicInteger();
        AtomicBoolean added = new AtomicBoolean();
        Map<String, RssTask.Outcome> outcomes = new LinkedHashMap<>();
        String fixtureId = "rss-add-fixture-" + UUID.randomUUID();
        String fixtureHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(fixtureId.getBytes(StandardCharsets.UTF_8)));
        HttpServer server = createRssServer(rssRequests, rssFailures,
                ConcurrentHashMap.newKeySet(), true, fixtureHash);
        DownloaderClient client = mock(DownloaderClient.class);
        Config config = ConfigUtil.snapshot();
        when(client.configurationSnapshot()).thenReturn(config);
        when(client.connect(anyBoolean())).thenReturn(DownloaderResult.success(null));
        when(client.torrents()).thenAnswer(invocation -> {
            if (!added.get()) return DownloaderResult.success(List.of());
            return DownloaderResult.success(List.of(new TorrentsInfo()
                    .setId("rss-add-fixture")
                    .setHash(fixtureHash)
                    .setName("RSS add fixture")
                    .setState(TorrentsStateEnum.downloading)
                    .setTagList(new ArrayList<>())
                    .setSavePath(ConfigUtil.CONFIG.getDownloadPathTemplate())));
        });
        when(client.download(any(), any(), any(), any()))
                .thenAnswer((Answer<DownloaderResult<Void>>) invocation -> {
                    addCalls.incrementAndGet();
                    added.set(true);
                    return DownloaderResult.success(null, fixtureId);
                });
        setTorrentClient(client);

        Ani fixture = AniUtil.createAni()
                .setId(fixtureId)
                .setTitle("RSS add fixture")
                .setBgmUrl("https://bgm.tv/subject/100001")
                .setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/rss/add-fixture")
                .setSubgroup("fixture")
                .setSeason(1)
                .setOffset(0)
                .setEnable(true);
        AniUtil.commit(List.of(fixture));
        Ani runtimeFixture = AniUtil.findRuntimeById(fixture.getId()).orElseThrow();
        RssTask.installTestHooks(ignored -> { },
                (id, outcome) -> outcomes.put(id, outcome));
        AtomicLong fakeNanos = new AtomicLong();
        TorrentUtil.installTestMonotonicClock(fakeNanos::get);
        try {
            RssTask.syncDownload(List.of(runtimeFixture));
        } finally {
            RssTask.resetTestHooks();
            TorrentUtil.resetTestMonotonicClock();
            TaskService.LOOP.set(false);
            server.stop(0);
            setTorrentClient(originalClient);
        }

        assertEquals(1, rssRequests.get());
        assertEquals(1, addCalls.get());
        assertTrue(added.get());
        assertEquals(RssTask.Outcome.SUCCESS, outcomes.get(fixture.getId()));
        writeAddFixtureReport(rssRequests.get(), addCalls.get(), outcomes);
    }

    private HttpServer createRssServer(AtomicInteger requests, AtomicInteger failures,
                                       Set<String> threads) throws IOException {
        return createRssServer(requests, failures, threads, false);
    }

    private HttpServer createRssServer(AtomicInteger requests, AtomicInteger failures,
                                       Set<String> threads, boolean includeAddFixture) throws IOException {
        return createRssServer(requests, failures, threads, includeAddFixture,
                "0123456789abcdef0123456789abcdef01234567");
    }

    private HttpServer createRssServer(AtomicInteger requests, AtomicInteger failures,
                                       Set<String> threads, boolean includeAddFixture,
                                       String addFixtureHash) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rss", exchange -> {
            requests.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            String fixture = includeAddFixture
                    && exchange.getRequestURI().getPath().endsWith("/add-fixture")
                    ? "<item><title>RSS add fixture - 01</title>"
                    + "<guid>" + addFixtureHash + "</guid>"
                    + "<enclosure url=\"magnet:?xt=urn:btih:" + addFixtureHash + "\" length=\"1\"/>"
                    + "</item>"
                    : "";
            byte[] payload = ("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
                    + fixture)
                    .getBytes(StandardCharsets.UTF_8);
            byte[] suffix = "</channel></rss>".getBytes(StandardCharsets.UTF_8);
            byte[] body = new byte[payload.length + suffix.length];
            System.arraycopy(payload, 0, body, 0, payload.length);
            System.arraycopy(suffix, 0, body, payload.length, suffix.length);
            try {
                exchange.getResponseHeaders().add("Content-Type", "application/rss+xml");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException e) {
                failures.incrementAndGet();
                throw e;
            } finally {
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private void writeAddFixtureReport(int rssRequests, int addCalls,
                                       Map<String, RssTask.Outcome> outcomes) throws Exception {
        Path output = Path.of("target", "w6-performance-data", "w6-rss-add-fixture.json")
                .toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("commit", gitOutput("rev-parse", "HEAD"));
        report.put("measurementKind", "real-rss-add-fixture");
        report.put("fixture", "one parseable magnet RSS item submitted through RssTask and DownloadService");
        report.put("rssRequests", rssRequests);
        report.put("downloaderAddCalls", addCalls);
        report.put("outcomes", outcomes);
        Files.writeString(output, reportJson(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("W6_ADD_FIXTURE_REPORT=" + output);
    }

    private List<Ani> subscriptions(int port) {
        List<Ani> result = new ArrayList<>();
        for (int index = 0; index < SUBSCRIPTION_COUNT; index++) {
            String id = "w6-subscription-" + (index + 1);
            result.add(AniUtil.createAni()
                    .setId(id)
                    .setTitle("W6 fixture " + (index + 1))
                    .setBgmUrl("https://bgm.tv/subject/" + (10000 + index))
                    .setUrl("http://127.0.0.1:" + port + "/rss/" + id)
                    .setSubgroup("fixture")
                    .setSeason(1)
                    .setEnable(true));
        }
        return result;
    }

    private void writeReport(List<Ani> subscriptions, Map<String, RssTask.Outcome> outcomes,
                             int rssRequests, int rssFailures, int downloaderConnectCalls,
                             int downloaderListCalls, long virtualSleepMillis,
                             long elapsedMillis, Set<String> rssThreads,
                             Set<String> downloaderThreads, ListAniSnapshot status) throws Exception {
        String runId = "w6-rss-" + Instant.now().toString().replace(':', '-') + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String outputProperty = System.getProperty("fork.w6.output");
        Path output = outputProperty == null || outputProperty.isBlank()
                ? Path.of("target", "w6-performance-data", runId + ".json")
                : Path.of(outputProperty).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());

        List<Map<String, Object>> perSubscription = new ArrayList<>();
        for (Ani subscription : subscriptions) {
            perSubscription.add(Map.of(
                    "id", subscription.getId(),
                    "outcome", outcomes.get(subscription.getId()).name()));
        }
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("subscriptions", subscriptions.size());
        counters.put("rssTransportRequests", rssRequests);
        counters.put("rssTransportFailures", rssFailures);
        counters.put("downloaderConnectCalls", downloaderConnectCalls);
        counters.put("downloaderListCalls", downloaderListCalls);
        counters.put("downloaderFilesCalls", null);
        counters.put("virtualPerSubscriptionSleepMs", virtualSleepMillis);
        counters.put("wallClockElapsedMs", elapsedMillis);
        counters.put("failedCount", status.failedCount());
        counters.put("snapshotHits", SUBSCRIPTION_COUNT - downloaderListCalls);
        counters.put("snapshotMisses", downloaderListCalls);
        counters.put("snapshotDirtyEvents", null);
        counters.put("snapshotReadsAfterFiveSeconds", Math.max(0, downloaderListCalls - 1));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("runId", runId);
        report.put("commit", gitOutput("rev-parse", "HEAD"));
        report.put("dirty", trackedWorkingTreeDirty());
        report.put("buildIdentity", Path.of("target", "classes")
                .toAbsolutePath().normalize().toString());
        report.put("measurementKind", "java-service-chain");
        report.put("systemUnderTest", List.of(
                "RssTask", "SubscriptionDownloadQueue", "DownloadService", "TorrentUtil", "ItemsUtil"));
        report.put("stubbedBoundaries", List.of("synthetic RSS HTTP transport", "DownloaderClient"));
        report.put("scenario", "rss-cycle-100-enabled");
        report.put("clockMode", "shared-fake-monotonic-and-rss-sleeper");
        report.put("environment", Map.of(
                "java", System.getProperty("java.version"),
                "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"),
                "configRoot", ISOLATED_CONFIG.toString()));
        report.put("fixture", Map.of(
                "enabledSubscriptions", subscriptions.size(),
                "rssBody", "parseable-empty-channel",
                "fixtureHash", sha256(subscriptions.stream()
                        .map(Ani::getId).reduce("", (left, right) -> left + "\n" + right))));
        report.put("samples", List.of(Map.of(
                "elapsedMs", elapsedMillis,
                "outcomes", perSubscription,
                "rssThreads", rssThreads,
                "downloaderThreads", downloaderThreads,
                "workerRunningAfterReturn", status.running())));
        report.put("counters", counters);
        report.put("assertions", List.of(
                "100 real subscription ids reached ItemsUtil through RssTask",
                "the shared monotonic clock forced a fresh downloader snapshot at t=5s boundaries",
                "all 100 actual subscription outcomes were SUCCESS",
                "500ms per-subscription throttle was measured as virtual time, not removed"));
        report.put("limitations", List.of(
                "This 100-subscription timing fixture uses an empty RSS channel; the real add/download mutation fixture is covered separately.",
                "Downloader files/add/delete/move/rename counters are null because those operations were not invoked.",
                "Snapshot dirty events are not instrumented in this run; hit/miss and post-boundary reads are derived from the actual mock transport calls.",
                "Only external RSS HTTP and downloader boundaries are synthetic; Spring services, queue, parser and worker are real."));
        Files.writeString(output, reportJson(report) + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("W6_REPORT=" + output);
    }

    private static String reportJson(Object report) {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(report);
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
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

    private static void setTorrentClient(DownloaderClient client) throws Exception {
        var field = TorrentUtil.class.getDeclaredField("CLIENT");
        field.setAccessible(true);
        field.set(null, client);
    }

    private record ListAniSnapshot(boolean running, int failedCount, long finishedAt) {
        private ListAniSnapshot(ani.rss.entity.ListAni.Refresh refresh) {
            this(refresh.isRunning(), refresh.getFailedCount(), refresh.getFinishedAt());
        }
    }
}
