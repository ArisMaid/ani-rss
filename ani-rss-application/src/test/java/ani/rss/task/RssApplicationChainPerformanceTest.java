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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
                virtualSleepMillis::addAndGet,
                (id, outcome) -> outcomes.put(id, outcome));
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
        assertEquals(1, downloaderListCalls.get(),
                "one successful downloader snapshot is reused by the real worker cycle");
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

    private HttpServer createRssServer(AtomicInteger requests, AtomicInteger failures,
                                       Set<String> threads) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rss", exchange -> {
            requests.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            byte[] payload = "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel>"
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
        counters.put("snapshotHits", null);
        counters.put("snapshotMisses", null);
        counters.put("snapshotDirtyEvents", null);
        counters.put("snapshotReadsAfterFiveSeconds", null);

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
        report.put("clockMode", "real-with-virtual-rss-sleep");
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
                "one successful downloader list snapshot served the cycle",
                "all 100 actual subscription outcomes were SUCCESS",
                "500ms per-subscription throttle was measured as virtual time, not removed"));
        report.put("limitations", List.of(
                "RSS body is a valid empty channel, so the real add/download mutation path is not exercised in this scenario.",
                "Downloader files/add/delete/move/rename counters are null because those operations were not invoked.",
                "Snapshot hit/miss/dirty sub-counters are not instrumented in this run; downloader list calls are actual mock transport calls.",
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
