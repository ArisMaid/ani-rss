package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.entity.Ani;
import ani.rss.entity.AnimeGarden;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.Mikan;
import ani.rss.entity.MikanInfo;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the real public-list/cache services with isolated synthetic
 * boundaries.  It is deliberately a fixture audit, not a model of service
 * latency: every external boundary and every unmeasured counter is recorded
 * in the raw report.
 */
class W6FixturePerformanceTest {
    private static final int MIKAN_ENTRY_COUNT = 96;
    private static final int IMAGE_REFERENCE_COUNT = 50;
    private static final String PRIVATE_ALLOWLIST = "ANI_RSS_IMAGE_PRIVATE_ALLOWLIST";
    private static final byte[] IMAGE_V1 = imageBytes(new Color(210, 70, 70));
    private static final byte[] IMAGE_V2 = imageBytes(new Color(70, 90, 210));

    private Path tempDir;

    private String originalConfigPath;
    private Config originalConfig;
    private HttpServer imageServer;
    private ImageCacheService imageCache;
    private CacheService coverCache;
    private PublicScoreService gardenScores;
    private PublicScoreService mikanScores;

    @BeforeEach
    void setUp() {
        tempDir = Path.of("target", "w6-service-fixture-" + UUID.randomUUID())
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create isolated W6 fixture directory", e);
        }
        originalConfigPath = System.getProperty("CONFIG");
        originalConfig = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
        Config isolated = ConfigUtil.copy(originalConfig)
                .setLogin(new Login().setUsername("w6-user")
                        .setPassword(AuthService.encodePassword("w6-password")))
                .setMultiLoginForbidden(false)
                .setProxy(false);
        ConfigUtil.sync(isolated);
        AuthService.reload();
        AniUtil.ANI_LIST.clear();
    }

    @AfterEach
    void tearDown() {
        if (imageCache != null) {
            imageCache.closeImageClients();
        }
        if (coverCache != null) {
            coverCache.close();
        }
        if (gardenScores != null) {
            gardenScores.stopWarmupExecutors();
        }
        if (mikanScores != null) {
            mikanScores.stopWarmupExecutors();
        }
        if (imageServer != null) {
            imageServer.stop(0);
        }
        SafeImageFetcher.closeCachedClients();
        AuthService.invalidateSessions();
        AniUtil.ANI_LIST.clear();
        if (originalConfig != null) {
            ConfigUtil.sync(originalConfig);
        }
        if (originalConfigPath == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfigPath);
        }
        System.clearProperty(PRIVATE_ALLOWLIST);
    }

    @Test
    void measuresMikanAnimeGardenAndImageFixturesThroughRealConsumers() throws Exception {
        AtomicInteger mikanListLoads = new AtomicInteger();
        mikanScores = emptyMikanScoreService();
        Mikan mikanFixture = mikanFixture();
        MikanService mikan = new MikanService(mikanScores, (text, season) -> {
            mikanListLoads.incrementAndGet();
            return mikanFixture;
        });

        String mikanQuery = "w6-mikan-" + UUID.randomUUID();
        Mikan mikanResult = mikan.list(mikanQuery, new Mikan.Season());
        List<MikanInfo> mikanItems = mikanResult.getWeeks().stream()
                .flatMap(week -> week.getItems().stream())
                .toList();
        long largestGroup = mikanItems.stream()
                .map(MikanInfo::getGroups)
                .filter(groups -> groups != null && !groups.isEmpty())
                .mapToLong(groups -> groups.get(0).getItems().size())
                .max()
                .orElse(0);
        assertEquals(1, mikanListLoads.get());
        assertEquals(MIKAN_ENTRY_COUNT, mikanItems.size());
        assertTrue(mikanResult.getWeeks().size() >= 2);
        assertTrue(largestGroup > 48, "fixture must retain a group larger than one score batch");
        assertTrue(mikanItems.stream().allMatch(item -> item.getScore() == null),
                "cold optional scores remain unknown rather than becoming zero");

        AtomicInteger gardenListLoads = new AtomicInteger();
        List<AnimeGarden.Subject> defaultSubjects = List.of(
                gardenSubject("900001", 1), gardenSubject("900002", 2));
        List<AnimeGarden.Subject> searchSubjects = List.of(gardenSubject("900003", 3));
        JsonObject coverIndex = new JsonObject();
        coverIndex.add("900001", GsonSupport.toTree(
                new BgmInfo.Images().setSmall("http://fixture/900001.png")));
        coverIndex.add("900003", GsonSupport.toTree(
                new BgmInfo.Images().setSmall("http://fixture/900003.png")));
        coverCache = new CacheService(() -> coverIndex) {
            @Override
            public JsonObject getBgmCoverSnapshot() {
                return coverIndex.deepCopy();
            }

            @Override
            public CoverLookup getBgmCoverForEnrichmentSnapshot() {
                return new CoverLookup(coverIndex.deepCopy(), true,
                        System.currentTimeMillis() + 60_000, 0);
            }
        };
        gardenScores = scoreServiceFor(Set.of("900001", "900002", "900003"));
        AnimeGardenService garden = new AnimeGardenService(
                () -> {
                    gardenListLoads.incrementAndGet();
                    return defaultSubjects;
                },
                coverCache,
                gardenScores,
                subjectId -> new BgmInfo()
                        .setId(subjectId)
                        .setName("Search subject " + subjectId)
                        .setImages(new BgmInfo.Images().setSmall("http://fixture/" + subjectId + ".png"))
                        .setRating(new BgmInfo.Rating().setScore(8.0))
        );

        List<AnimeGarden.Week> defaultResult = garden.list("");
        List<AnimeGarden.Week> searchResult = garden.list("https://bgm.tv/subject/900003");
        AnimeGarden.EnrichmentResponse defaultEnrichment = garden.enrich(List.of("900001", "900002"));
        AnimeGarden.EnrichmentResponse searchEnrichment = garden.enrich(List.of("900003"));
        assertEquals(1, gardenListLoads.get(), "search uses its own consumer snapshot");
        assertTrue(defaultResult.stream().flatMap(week -> week.getSubjects().stream())
                .anyMatch(subject -> "900001".equals(subject.getId())));
        assertEquals("900003", searchResult.get(0).getSubjects().get(0).getId());
        assertEquals("http://fixture/900001.png",
                defaultEnrichment.getSubjects().get("900001").getCover());
        assertEquals(0.0, defaultEnrichment.getSubjects().get("900002").getScore());
        assertEquals(8.0, searchEnrichment.getSubjects().get("900003").getScore());
        assertTrue(garden.enrich(List.of("900001")).getRetryableSubjectIds().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> garden.enrich(List.of("900999")));

        ImageFixtureResult images = measureImages();
        assertEquals(IMAGE_REFERENCE_COUNT, images.logicalReferences());
        assertEquals(25, images.uniqueUrls());
        assertEquals(27, images.upstreamRequests(),
                "25 unique references plus the two same-key version observations");
        assertNotEquals(images.versionOne().etag(), images.versionTwo().etag());
        assertTrue(images.streamBytes() > 0);

        writeReport(mikanListLoads.get(), mikanItems, largestGroup,
                gardenListLoads.get(), defaultResult, searchResult,
                defaultEnrichment, searchEnrichment, images);
    }

    private ImageFixtureResult measureImages() throws Exception {
        AtomicInteger upstreamRequests = new AtomicInteger();
        AtomicInteger version = new AtomicInteger(1);
        Map<String, AtomicInteger> requestByPath = new ConcurrentHashMap<>();
        imageServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imageServer.createContext("/images", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requestByPath.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            upstreamRequests.incrementAndGet();
            byte[] payload = path.endsWith("/versioned") && version.get() > 1 ? IMAGE_V2 : IMAGE_V1;
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, payload.length);
            try {
                exchange.getResponseBody().write(payload);
            } finally {
                exchange.close();
            }
        });
        imageServer.start();
        imageCache = new ImageCacheService();
        MockHttpServletResponse firstLogin = login();
        MockHttpServletResponse secondLogin = login();
        String base = "http://127.0.0.1:" + imageServer.getAddress().getPort() + "/images/";
        Set<String> uniqueUrls = ConcurrentHashMap.newKeySet();
        long streamBytes = 0;
        for (int index = 0; index < IMAGE_REFERENCE_COUNT; index++) {
            String url = base + "fixture-" + (index % 25);
            uniqueUrls.add(url);
            MockHttpServletResponse login = index % 2 == 0 ? firstLogin : secondLogin;
            ImageCacheService.PublicImage image = imageCache.publicImage(url,
                    authenticated(login, "GET"));
            assertTrue(Files.isRegularFile(image.path()));
            assertTrue(Files.size(image.path()) > 0);
        }

        String versionedUrl = base + "versioned";
        ImageCacheService.PublicImage versionOne = imageCache.publicImage(
                versionedUrl, authenticated(firstLogin, "GET"));
        removePublicEntry(imageCache, versionedUrl);
        version.set(2);
        ImageCacheService.PublicImage versionTwo = imageCache.publicImage(
                versionedUrl, authenticated(secondLogin, "GET"));
        try (ImageCacheService.PublicImageHandle handle = imageCache.openPublicImage(
                versionedUrl, authenticated(firstLogin, "GET"))) {
            streamBytes = handle.inputStream().readAllBytes().length;
        }
        return new ImageFixtureResult(
                IMAGE_REFERENCE_COUNT, uniqueUrls.size(), upstreamRequests.get(),
                versionOne, versionTwo, streamBytes, requestByPath);
    }

    private void writeReport(int mikanLoads, List<MikanInfo> mikanItems, long largestGroup,
                             int gardenLoads, List<AnimeGarden.Week> defaultResult,
                             List<AnimeGarden.Week> searchResult,
                             AnimeGarden.EnrichmentResponse defaultEnrichment,
                             AnimeGarden.EnrichmentResponse searchEnrichment,
                             ImageFixtureResult images) throws Exception {
        String runId = "w6-services-" + Instant.now().toString().replace(':', '-') + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String property = System.getProperty("fork.w6.fixture.output");
        Path output = property == null || property.isBlank()
                ? Path.of("target", "w6-performance-data", runId + ".json")
                : Path.of(property).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", 1);
        report.put("runId", runId);
        report.put("commit", gitOutput("rev-parse", "HEAD"));
        report.put("dirty", trackedWorkingTreeDirty());
        report.put("buildIdentity", Path.of("target", "classes")
                .toAbsolutePath().normalize().toString());
        report.put("measurementKind", "service-fixture");
        report.put("systemUnderTest", List.of(
                "MikanService", "PublicScoreService", "AnimeGardenService", "CacheService",
                "ImageCacheService", "SafeImageFetcher"));
        report.put("stubbedBoundaries", List.of(
                "Mikan list loader", "AnimeGarden subject/search loader", "local image HTTP transport"));
        report.put("scenario", "mikan-96-anime-garden-two-consumers-images-50");
        report.put("clockMode", "real");
        report.put("environment", Map.of(
                "java", System.getProperty("java.version"),
                "os", System.getProperty("os.name"),
                "arch", System.getProperty("os.arch"),
                "configRoot", tempDir.toAbsolutePath().normalize().toString()));
        report.put("fixture", Map.of(
                "mikanEntries", mikanItems.size(),
                "mikanWeeks", 2,
                "mikanLargestGroupItems", largestGroup,
                "animeGardenDefaultConsumers", 1,
                "animeGardenSearchConsumers", 1,
                "imageLogicalReferences", images.logicalReferences(),
                "imageUniqueUrls", images.uniqueUrls(),
                "imageBytes", Base64.getEncoder().encodeToString(IMAGE_V1),
                "fixtureHash", sha256(mikanItems.stream().map(MikanInfo::getUrl)
                        .reduce("", (left, right) -> left + "\n" + right))));
        report.put("samples", List.of(Map.of(
                "mikanListLoaderCalls", mikanLoads,
                "animeGardenDefaultListLoaderCalls", gardenLoads,
                "animeGardenDefaultWeekCount", defaultResult.size(),
                "animeGardenSearchWeekCount", searchResult.size(),
                "animeGardenDefaultEnrichmentKeys", defaultEnrichment.getSubjects().keySet(),
                "animeGardenSearchEnrichmentKeys", searchEnrichment.getSubjects().keySet(),
                "imageUpstreamRequests", images.upstreamRequests(),
                "imageRequestByPath", images.requestByPath(),
                "imageStreamBytes", images.streamBytes())));
        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("rssTransportRequests", null);
        counters.put("downloaderListCalls", null);
        counters.put("dbQueries", null);
        counters.put("fileWalks", null);
        counters.put("imageUpstreamRequests", images.upstreamRequests());
        counters.put("imageMaxExternalConcurrency", null);
        report.put("counters", counters);
        report.put("assertions", List.of(
                "96 Mikan entries span at least two weeks and one group larger than 48",
                "AnimeGarden default and single-subject search snapshots remain independently enrichable",
                "50 logical image references use 25 shared URLs across two sessions",
                "same-key image content version change produces a new ETag",
                "image stream is opened and fully read through the production lease handle"));
        report.put("limitations", List.of(
                "Mikan and AnimeGarden external list/search transports are injected fixture loaders; the production consumers are real.",
                "No real downloader, external source account, production database, or user media is touched.",
                "DB query, lock-wait, file-walk, and queue-depth counters are explicitly unmeasured (null), not inferred from fixture size.",
                "Image version change is forced through the cache's exact-entry maintenance path to make the replacement observable in one isolated run."));
        Files.writeString(output, reportJson(report)
                + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("W6_FIXTURE_REPORT=" + output);
    }

    private static PublicScoreService emptyMikanScoreService() {
        return new PublicScoreService(id -> null, url -> "") {
            @Override
            MikanScoreLookup getCachedMikanScoreLookupAndWarm(
                    java.util.Collection<MikanInfo> infos, int maxColdWarmups) {
                return new MikanScoreLookup(Map.of(), Set.of());
            }
        };
    }

    private static String reportJson(Object report) {
        return new GsonBuilder()
                .disableHtmlEscaping()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(report);
    }

    private static PublicScoreService scoreServiceFor(Set<String> ids) {
        Map<String, Double> scores = new LinkedHashMap<>();
        scores.put("900001", 8.1);
        scores.put("900002", 0.0);
        scores.put("900003", 8.0);
        return new PublicScoreService(id -> null, url -> "") {
            @Override
            public BgmScoreLookup getCachedBgmScoresAndWarm(java.util.Collection<String> subjectIds) {
                Map<String, Double> result = new LinkedHashMap<>();
                for (String id : subjectIds) {
                    if (ids.contains(id)) {
                        result.put(id, scores.get(id));
                    }
                }
                return new BgmScoreLookup(result, Set.of());
            }
        };
    }

    private static Mikan mikanFixture() {
        List<MikanInfo> first = new ArrayList<>();
        List<MikanInfo> second = new ArrayList<>();
        for (int index = 0; index < MIKAN_ENTRY_COUNT; index++) {
            MikanInfo item = new MikanInfo()
                    .setUrl("https://mikanani.me/Home/Bangumi/" + (700000 + index))
                    .setTitle("W6 Mikan " + index)
                    .setScore(null)
                    .setGroups(List.of(new Mikan.Group()
                            .setSubgroupId("large-group")
                            .setLabel("large-group")
                            .setItems(List.of(new Mikan.Item()
                                    .setTitle("fixture item " + index)))));
            if (index < MIKAN_ENTRY_COUNT / 2) {
                first.add(item);
            } else {
                second.add(item);
            }
        }
        // A single group with 64 items makes the >48 batching condition
        // explicit without pretending that score calls were performed here.
        List<Mikan.Item> largeItems = new ArrayList<>();
        for (int index = 0; index < 64; index++) {
            largeItems.add(new Mikan.Item().setTitle("large fixture item " + index));
        }
        first.get(0).setGroups(List.of(new Mikan.Group()
                .setSubgroupId("large-group")
                .setLabel("large-group")
                .setItems(largeItems)));
        return new Mikan()
                .setSeasons(List.of())
                .setWeeks(List.of(
                        new Mikan.Week().setWeekLabel("星期一").setItems(first),
                        new Mikan.Week().setWeekLabel("星期四").setItems(second)))
                .setTotalItem(MIKAN_ENTRY_COUNT);
    }

    private static AnimeGarden.Subject gardenSubject(String id, int dayOffset) {
        return new AnimeGarden.Subject()
                .setId(id)
                .setName("Garden " + id)
                .setActivedAt(new Date(System.currentTimeMillis() + dayOffset * 86_400_000L));
    }

    private static byte[] imageBytes(Color color) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < image.getWidth(); x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    image.setRGB(x, y, color.getRGB());
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MockHttpServletResponse login() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.login("w6-user", "w6-password", new MockHttpServletRequest(), response);
        return response;
    }

    private static MockHttpServletRequest authenticated(MockHttpServletResponse login, String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI("/api/v2/images");
        Cookie session = cookie(login, AuthService.SESSION_COOKIE);
        Cookie csrf = cookie(login, AuthService.CSRF_COOKIE);
        request.setCookies(session, csrf);
        request.addHeader(AuthService.CSRF_HEADER, csrf.getValue());
        return request;
    }

    private static Cookie cookie(MockHttpServletResponse response, String name) {
        String header = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .findFirst().orElseThrow();
        return new Cookie(name, header.substring(name.length() + 1, header.indexOf(';')));
    }

    @SuppressWarnings("unchecked")
    private static void removePublicEntry(ImageCacheService service, String url) throws Exception {
        var entriesField = ImageCacheService.class.getDeclaredField("publicEntries");
        entriesField.setAccessible(true);
        Map<String, Object> entries = (Map<String, Object>) entriesField.get(service);
        String key = cn.hutool.crypto.SecureUtil.sha256(url);
        Object entry = entries.get(key);
        assertNotNull(entry);
        var remove = ImageCacheService.class.getDeclaredMethod(
                "removePublicEntry", entry.getClass());
        remove.setAccessible(true);
        assertTrue((Boolean) remove.invoke(service, entry));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
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

    private record ImageFixtureResult(int logicalReferences, int uniqueUrls,
                                      int upstreamRequests,
                                      ImageCacheService.PublicImage versionOne,
                                      ImageCacheService.PublicImage versionTwo,
                                      long streamBytes,
                                      Map<String, AtomicInteger> requestByPath) {
    }

    private static final class GsonSupport {
        private static com.google.gson.JsonElement toTree(Object value) {
            return ani.rss.commons.GsonStatic.GSON.toJsonTree(value);
        }
    }
}
