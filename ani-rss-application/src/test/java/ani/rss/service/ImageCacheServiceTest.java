package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.auth.AuthenticationFailureException;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.InetSocketAddress;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageCacheServiceTest {
    private static final String PRIVATE_ALLOWLIST = "ANI_RSS_IMAGE_PRIVATE_ALLOWLIST";
    /** Retained on purpose: JUnit TempDir cleanup would recursively delete fixture data. */
    private Path tempDir;
    private String originalConfigPath;
    private Config original;

    @BeforeEach
    void setUp() throws IOException {
        originalConfigPath = System.getProperty("CONFIG");
        tempDir = Path.of("target", "fork-image-cache-" + UUID.randomUUID())
                .toAbsolutePath().normalize();
        Files.createDirectories(tempDir);
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Config config = ConfigUtil.copy(original)
                .setLogin(new Login().setUsername("image-user")
                        .setPassword(AuthService.encodePassword("image-password")))
                .setMultiLoginForbidden(false);
        System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
        ConfigUtil.sync(config);
        AuthService.reload();
    }

    @AfterEach
    void tearDown() {
        SafeImageFetcher.closeCachedClients();
        AuthService.invalidateSessions();
        ConfigUtil.sync(original);
        System.clearProperty(PRIVATE_ALLOWLIST);
        if (originalConfigPath == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfigPath);
        }
        LogUtil.loadLogback();
    }

    @Test
    void deduplicatesWithinSessionAndBindsIdsToThatSession() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            MockHttpServletResponse firstLogin = login();
            MockHttpServletRequest first = authenticated(firstLogin, "POST");
            ImageCacheService service = new ImageCacheService();

            ImageCacheService.ImageRef firstRef = service.cache(url, first);
            ImageCacheService.ImageRef duplicate = service.cache(url, first);
            assertEquals(firstRef.id(), duplicate.id());
            assertEquals(1, requests.get());

            MockHttpServletResponse secondLogin = login();
            MockHttpServletRequest second = authenticated(secondLogin, "POST");
            ImageCacheService.ImageRef secondRef = service.cache(url, second);
            assertNotEquals(firstRef.id(), secondRef.id());
            assertEquals(2, requests.get());
            assertThrows(IllegalArgumentException.class,
                    () -> service.resolve(firstRef.id(), authenticated(secondLogin, "GET")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingSessionAsAuthenticationFailure() {
        ImageCacheService service = new ImageCacheService();
        assertThrows(AuthenticationFailureException.class,
                () -> service.cache("https://example.com/image.png", new MockHttpServletRequest()));
    }

    @Test
    void rejectsNullImageUrlBeforeHashing() {
        MockHttpServletResponse login = login();
        ImageCacheService service = new ImageCacheService();

        assertThrows(IllegalArgumentException.class,
                () -> service.cache(null, authenticated(login, "POST")));
    }

    @Test
    void concurrentRequestsForSameSessionUseOneUpstreamFetch() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(100);
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, image.length);
                exchange.getResponseBody().write(image);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var executor = Executors.newFixedThreadPool(6);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            MockHttpServletResponse login = login();
            ImageCacheService service = new ImageCacheService();
            CountDownLatch ready = new CountDownLatch(6);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<String>> futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        return service.cache(url, authenticated(login, "POST")).id();
                    }))
                    .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            String expected = futures.get(0).get(10, TimeUnit.SECONDS);
            for (var future : futures) {
                assertEquals(expected, future.get(10, TimeUnit.SECONDS));
            }
            assertEquals(1, requests.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            server.stop(0);
        }
    }

    @Test
    void publicImageCacheIsSharedAcrossSessionsAndRestarts() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(100);
                exchange.getResponseHeaders().add("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, image.length);
                exchange.getResponseBody().write(image);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        var executor = Executors.newFixedThreadPool(6);
        ImageCacheService service = new ImageCacheService();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            MockHttpServletResponse firstLogin = login();
            MockHttpServletResponse secondLogin = login();
            CountDownLatch ready = new CountDownLatch(6);
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<ImageCacheService.PublicImage>> futures =
                    java.util.stream.IntStream.range(0, 6)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                start.await();
                                MockHttpServletResponse login = index % 2 == 0 ? firstLogin : secondLogin;
                                return service.publicImage(url, authenticated(login, "GET"));
                            }))
                            .toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            ImageCacheService.PublicImage expected = futures.get(0).get(10, TimeUnit.SECONDS);
            for (var future : futures) {
                ImageCacheService.PublicImage actual = future.get(10, TimeUnit.SECONDS);
                assertEquals(expected.path(), actual.path());
                assertEquals(expected.etag(), actual.etag());
            }
            assertEquals(1, requests.get());

            Path manifest = tempDir.resolve("image-cache").resolve("public")
                    .resolve("public-manifest.json");
            long manifestDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.isRegularFile(manifest) && System.nanoTime() < manifestDeadline) {
                Thread.sleep(10);
            }
            assertTrue(Files.isRegularFile(manifest), "coalesced manifest writer did not publish");

            ImageCacheService restarted = new ImageCacheService();
            try {
                ImageCacheService.PublicImage persisted = restarted.publicImage(
                        url, authenticated(secondLogin, "GET"));
                assertEquals(expected.path(), persisted.path());
                assertEquals(1, requests.get());
            } finally {
                restarted.closeImageClients();
            }

            assertThrows(IllegalArgumentException.class,
                    () -> service.publicImage(url + "?token=private", authenticated(firstLogin, "GET")));
        } finally {
            service.closeImageClients();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            server.stop(0);
        }
    }

    @Test
    void cachesShortUpstreamFailureAndAdvertisesRetryWindow() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt == 1) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        ImageCacheService service = new ImageCacheService();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            MockHttpServletResponse login = login();
            MockHttpServletRequest request = authenticated(login, "GET");
            assertThrows(UpstreamServiceException.class,
                    () -> service.publicImage(url, request));
            UpstreamServiceException cached = assertThrows(UpstreamServiceException.class,
                    () -> service.publicImage(url, request));
            assertTrue(cached.retryAfterSeconds() > 0);
            assertEquals(1, requests.get());
        } finally {
            service.closeImageClients();
            server.stop(0);
        }
    }

    @Test
    void publicReadLeaseDefersEvictionUntilTheResponseCloses() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        ImageCacheService service = new ImageCacheService();
        ImageCacheService.PublicImageHandle handle = null;
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            handle = service.openPublicImage(url, authenticated(login(), "GET"));
            Object entry = publicEntries(service).values().iterator().next();
            Method remove = ImageCacheService.class.getDeclaredMethod("removePublicEntry", entry.getClass());
            remove.setAccessible(true);

            assertEquals(false, remove.invoke(service, entry));
            assertTrue(java.nio.file.Files.exists(handle.image().path()));

            handle.close();
            handle = null;
            assertEquals(true, remove.invoke(service, entry));
            assertTrue(java.nio.file.Files.notExists(handlePath(entry)));
        } finally {
            if (handle != null) handle.close();
            service.closeImageClients();
            server.stop(0);
        }
    }

    @Test
    void staleCleanupCannotDeleteAReplacementPublishedForTheSameKey() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        ImageCacheService service = new ImageCacheService();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            service.publicImage(url, authenticated(login(), "GET"));
            Object oldEntry = publicEntries(service).values().iterator().next();
            String key = cn.hutool.crypto.SecureUtil.sha256(url);
            Method load = ImageCacheService.class.getDeclaredMethod(
                    "loadPublicImage", String.class, String.class, CompletableFuture.class);
            load.setAccessible(true);
            CompletableFuture<Object> replacement = new CompletableFuture<>();
            load.invoke(service, url, key, replacement);
            replacement.get(5, TimeUnit.SECONDS);

            assertEquals(1, requests.get(), "late producer must re-use the published cache entry");
            Object current = publicEntries(service).get(key);
            assertTrue(current == oldEntry);
            assertTrue(java.nio.file.Files.exists(handlePath(current)));
        } finally {
            service.closeImageClients();
            server.stop(0);
        }
    }

    @Test
    void publicFailureRecordsExpireAndStayBounded() throws Exception {
        ImageCacheService service = new ImageCacheService();
        try {
            Map<String, Object> failures = publicFailures(service);
            Class<?> failureType = Class.forName(
                    "ani.rss.service.ImageCacheService$FailureEntry");
            var constructor = failureType.getDeclaredConstructor(long.class, String.class);
            constructor.setAccessible(true);
            long now = System.currentTimeMillis();
            failures.put("expired", constructor.newInstance(now - 1, "fetch"));
            for (int index = 0; index < 5_001; index++) {
                failures.put("key-" + index, constructor.newInstance(now + 60_000L + index, "fetch"));
            }
            Method trim = ImageCacheService.class.getDeclaredMethod("trimPublicFailures");
            trim.setAccessible(true);
            trim.invoke(service);
            assertTrue(failures.size() <= 5_000);
            assertTrue(!failures.containsKey("expired"));
        } finally {
            service.closeImageClients();
        }
    }

    @Test
    void readerContentionUsesShortRetryWithoutPoisoningThePublicFailureCache() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, image.length);
            exchange.getResponseBody().write(image);
            exchange.close();
        });
        server.start();
        ImageCacheService service = new ImageCacheService();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";
            service.publicImage(url, authenticated(login(), "GET"));
            String key = cn.hutool.crypto.SecureUtil.sha256(url);
            Object current = publicEntries(service).get(key);
            Class<?> entryType = current.getClass();
            var constructor = entryType.getDeclaredConstructor(
                    String.class, String.class, Path.class, String.class, String.class,
                    long.class, long.class, long.class, AtomicInteger.class);
            constructor.setAccessible(true);
            Object expiredBusy = constructor.newInstance(
                    recordValue(current, "key"), recordValue(current, "url"), handlePath(current),
                    recordValue(current, "contentType"), recordValue(current, "etag"),
                    recordValue(current, "fetchedAt"), System.currentTimeMillis() - 1,
                    recordValue(current, "length"), new AtomicInteger(1));
            publicEntries(service).put(key, expiredBusy);

            Method load = ImageCacheService.class.getDeclaredMethod(
                    "loadPublicImage", String.class, String.class, CompletableFuture.class);
            load.setAccessible(true);
            CompletableFuture<Object> future = new CompletableFuture<>();
            load.invoke(service, url, key, future);

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(5, TimeUnit.SECONDS));
            UpstreamServiceException upstream = assertInstanceOf(
                    UpstreamServiceException.class, failure.getCause());
            assertEquals(1, upstream.retryAfterSeconds());
            assertFalse(publicFailures(service).containsKey(key));
        } finally {
            service.closeImageClients();
            server.stop(0);
        }
    }

    @Test
    void shutdownCompletesFlightsThatWereQueuedBeforeTheExecutorStopped() throws Exception {
        ImageCacheService service = new ImageCacheService();
        try {
            CompletableFuture<Object> queued = new CompletableFuture<>();
            publicFlights(service).put("queued", queued);

            service.closeImageClients();

            assertTrue(queued.isCompletedExceptionally());
            assertTrue(publicFlights(service).isEmpty());
        } finally {
            service.closeImageClients();
        }
    }

    @Test
    void manifestWriterKeepsARevisionPublishedDuringAnInFlightSnapshot() throws Exception {
        CountDownLatch firstWriteStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        List<String> payloads = new CopyOnWriteArrayList<>();
        ImageCacheService service = new ImageCacheService((root, json) -> {
            payloads.add(json);
            if (first.compareAndSet(true, false)) {
                firstWriteStarted.countDown();
                try {
                    if (!releaseFirstWrite.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("manifest barrier timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("manifest barrier interrupted", e);
                }
            }
        });
        try {
            markManifestDirty(service);
            assertTrue(firstWriteStarted.await(5, TimeUnit.SECONDS));
            long firstRevision = service.publicCacheDiagnostics().currentRevision();

            markManifestDirty(service);
            releaseFirstWrite.countDown();
            awaitCondition(() -> service.publicCacheDiagnostics().persistedRevision()
                    == service.publicCacheDiagnostics().currentRevision()
                    && service.publicCacheDiagnostics().writeSuccesses() >= 2);

            ImageCacheService.PublicCacheDiagnostics diagnostics = service.publicCacheDiagnostics();
            assertTrue(diagnostics.currentRevision() > firstRevision);
            assertEquals(diagnostics.currentRevision(), diagnostics.persistedRevision());
            assertEquals(2, payloads.size());
            assertFalse(diagnostics.dirty());
        } finally {
            releaseFirstWrite.countDown();
            service.closeImageClients();
        }
    }

    @Test
    void closeFlushesTheLatestManifestAndReportsClosedLifecycle() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        ImageCacheService service = new ImageCacheService((root, json) -> writes.incrementAndGet());
        markManifestDirty(service);

        service.closeImageClients();

        ImageCacheService.PublicCacheDiagnostics diagnostics = service.publicCacheDiagnostics();
        assertEquals("CLOSED", diagnostics.lifecycle());
        assertEquals("success", diagnostics.finalFlushStatus());
        assertEquals(diagnostics.currentRevision(), diagnostics.persistedRevision());
        assertTrue(writes.get() >= 1);
        service.closeImageClients();
    }

    @Test
    void closeReportsFinalManifestFailureWithoutClaimingPersistence() throws Exception {
        ImageCacheService service = new ImageCacheService((root, json) -> {
            throw new IOException("manifest test failure");
        });
        markManifestDirty(service);

        service.closeImageClients();

        ImageCacheService.PublicCacheDiagnostics diagnostics = service.publicCacheDiagnostics();
        assertEquals("CLOSED", diagnostics.lifecycle());
        assertEquals("failed", diagnostics.finalFlushStatus());
        assertTrue(diagnostics.writeFailures() >= 1);
        assertTrue(diagnostics.dirty());
        assertNotEquals(diagnostics.currentRevision(), diagnostics.persistedRevision());
    }

    @Test
    void closeReportsTimeoutWhenTheSingleManifestWriterCannotReleaseItsLease() throws Exception {
        CountDownLatch writeStarted = new CountDownLatch(1);
        ImageCacheService service = new ImageCacheService((root, json) -> {
            writeStarted.countDown();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_500);
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignored) {
                    // Simulate a filesystem call that does not abort immediately.
                }
            }
        });
        markManifestDirty(service);
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));

        long started = System.nanoTime();
        service.closeImageClients();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis >= 4_500);
        assertEquals("timeout", service.publicCacheDiagnostics().finalFlushStatus());
    }

    @Test
    void pendingDeletionIsPathDeduplicatedAndUsesBoundedBackoff() throws Exception {
        ImageCacheService service = new ImageCacheService();
        try {
            Path root = tempDir.resolve("image-cache").resolve("public");
            Files.createDirectories(root);
            Path blocked = root.resolve("blocked-old-image");
            Files.createDirectory(blocked);
            Files.writeString(blocked.resolve("child"), "keep");
            Method remember = ImageCacheService.class.getDeclaredMethod(
                    "rememberPendingDeletion", String.class, Path.class, long.class);
            remember.setAccessible(true);
            assertEquals(true, remember.invoke(service, "key-a", blocked, 7L));
            assertEquals(true, remember.invoke(service, "key-b", blocked, 99L));
            assertEquals(1, service.publicCacheDiagnostics().pendingDeletionFiles());
            assertEquals(7, service.publicCacheDiagnostics().pendingDeletionBytes());

            Method retry = ImageCacheService.class.getDeclaredMethod("retryPendingDeletions");
            retry.setAccessible(true);
            retry.invoke(service);
            Map<String, Object> pending = pendingDeletions(service);
            Object value = pending.values().iterator().next();
            assertEquals(1, ((Integer) recordValue(value, "attempts")).intValue());
            long nextRetryAt = recordValue(value, "nextRetryAt");
            assertTrue(nextRetryAt > System.currentTimeMillis());
            long retries = service.publicCacheDiagnostics().pendingDeletionRetries();
            retry.invoke(service);
            assertEquals(retries, service.publicCacheDiagnostics().pendingDeletionRetries());
        } finally {
            service.closeImageClients();
        }
    }

    @Test
    void pendingDeletionAdmissionIsBoundedAndCountedInTrackedBudget() throws Exception {
        ImageCacheService service = new ImageCacheService();
        try {
            Path root = tempDir.resolve("image-cache").resolve("public");
            Files.createDirectories(root);
            Method remember = ImageCacheService.class.getDeclaredMethod(
                    "rememberPendingDeletion", String.class, Path.class, long.class);
            remember.setAccessible(true);
            for (int index = 0; index < 1_025; index++) {
                remember.invoke(service, "key-" + index,
                        root.resolve("pending-" + index), 1L);
            }
            ImageCacheService.PublicCacheDiagnostics diagnostics = service.publicCacheDiagnostics();
            assertEquals(1_024, diagnostics.pendingDeletionFiles());
            assertEquals(1_024, diagnostics.pendingDeletionBytes());
            assertEquals(1_024, diagnostics.trackedFiles());
            assertEquals(1_024, diagnostics.trackedBytes());
        } finally {
            service.closeImageClients();
        }
    }

    @Test
    void reservedBytesRejectAnOversizedAdmissionWithoutChangingReservation() throws Exception {
        ImageCacheService service = new ImageCacheService();
        try {
            Field reserved = ImageCacheService.class.getDeclaredField("publicReservedBytes");
            reserved.setAccessible(true);
            AtomicLong bytes = (AtomicLong) reserved.get(service);
            bytes.set(256L * 1024 * 1024);
            Method reserve = ImageCacheService.class.getDeclaredMethod(
                    "reservePublicCapacity", Class.forName(
                            "ani.rss.service.ImageCacheService$PublicEntry"), long.class);
            reserve.setAccessible(true);
            assertEquals(false, reserve.invoke(service, new Object[]{null, 1L}));
            assertEquals(256L * 1024 * 1024, bytes.get());
        } finally {
            service.closeImageClients();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> publicEntries(ImageCacheService service) throws Exception {
        Field field = ImageCacheService.class.getDeclaredField("publicEntries");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> publicFailures(ImageCacheService service) throws Exception {
        Field field = ImageCacheService.class.getDeclaredField("publicFailures");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CompletableFuture<Object>> publicFlights(ImageCacheService service)
            throws Exception {
        Field field = ImageCacheService.class.getDeclaredField("publicFlights");
        field.setAccessible(true);
        return (Map<String, CompletableFuture<Object>>) (Map<?, ?>) field.get(service);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pendingDeletions(ImageCacheService service) throws Exception {
        Field field = ImageCacheService.class.getDeclaredField("publicPendingDeletions");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(service);
    }

    private static void markManifestDirty(ImageCacheService service) throws Exception {
        Method mark = ImageCacheService.class.getDeclaredMethod("markPublicManifestDirty");
        mark.setAccessible(true);
        mark.invoke(service);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before the deadline");
    }

    private static <T> T recordValue(Object record, String name) throws Exception {
        Method accessor = record.getClass().getDeclaredMethod(name);
        accessor.setAccessible(true);
        @SuppressWarnings("unchecked")
        T value = (T) accessor.invoke(record);
        return value;
    }

    private static Path handlePath(Object entry) throws Exception {
        Method path = entry.getClass().getDeclaredMethod("path");
        path.setAccessible(true);
        return (Path) path.invoke(entry);
    }

    private static MockHttpServletResponse login() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthService.login("image-user", "image-password", new MockHttpServletRequest(), response);
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
}
