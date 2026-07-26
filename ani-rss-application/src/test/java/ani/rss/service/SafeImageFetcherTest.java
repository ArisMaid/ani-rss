package ani.rss.service;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeImageFetcherTest {
    private static final String PRIVATE_ALLOWLIST = "ANI_RSS_IMAGE_PRIVATE_ALLOWLIST";

    @AfterEach
    void closeClientPool() {
        SafeImageFetcher.closeCachedClients();
        System.clearProperty(PRIVATE_ALLOWLIST);
    }

    @Test
    void allowsExplicitLocalhostAndValidatesRedirectAndContentType() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        server.createContext("/redirect", exchange -> redirect(exchange, "/image"));
        server.createContext("/redirect-to-address", exchange -> redirect(exchange,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/image"));
        server.createContext("/image", exchange -> respond(exchange, 200, "image/png", image));
        server.createContext("/text", exchange -> respond(exchange, 200, "text/plain", image));
        server.createContext("/forged", exchange -> respond(exchange, 200, "image/png", new byte[]{1, 2, 3, 4}));
        server.createContext("/mismatch", exchange -> respond(exchange, 200, "image/jpeg", image));
        byte[] huge = image.clone();
        huge[16] = 0x00;
        huge[17] = 0x01;
        huge[18] = 0x00;
        huge[19] = 0x00;
        server.createContext("/huge-dimensions", exchange -> respond(exchange, 200, "image/png", huge));
        server.start();
        try {
            int port = server.getAddress().getPort();
            System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
            Config allowLocal = ConfigUtil.copy(ConfigUtil.CONFIG);
            SafeImageFetcher.FetchedImage fetched = SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + port + "/redirect", allowLocal);
            assertArrayEquals(image, fetched.bytes());
            assertThrows(IllegalStateException.class, () -> SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + port + "/text", allowLocal));
            assertThrows(IllegalStateException.class, () -> SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + port + "/forged", allowLocal));
            assertThrows(IllegalStateException.class, () -> SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + port + "/mismatch", allowLocal));
            assertThrows(IllegalStateException.class, () -> SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + port + "/huge-dimensions", allowLocal));
            System.setProperty(PRIVATE_ALLOWLIST, "localhost");
            Config allowHostnameOnly = ConfigUtil.copy(ConfigUtil.CONFIG);
            assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.fetch(
                    "http://localhost:" + port + "/redirect-to-address", allowHostnameOnly));
        } finally {
            System.clearProperty(PRIVATE_ALLOWLIST);
            server.stop(0);
        }
    }

    @Test
    void rejectsPrivateHostWithoutExplicitAllowlist() {
        Config config = ConfigUtil.copy(ConfigUtil.CONFIG);
        assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.fetch(
                "http://127.0.0.1:1/image", config));
    }

    @Test
    void reusesOnePooledConnectionForRepeatedThumbnailFetches() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        Set<Integer> remotePorts = ConcurrentHashMap.newKeySet();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/image", exchange -> {
            remotePorts.add(exchange.getRemoteAddress().getPort());
            respond(exchange, 200, "image/png", image);
        });
        server.start();
        try {
            System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
            Config config = ConfigUtil.copy(ConfigUtil.CONFIG);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/image";

            SafeImageFetcher.fetch(url, config);
            SafeImageFetcher.fetch(url, config);

            assertEquals(1, remotePorts.size(), "the second cover should reuse the pooled connection");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void classifiesReservedIpv4AndIpv6Addresses() throws Exception {
        assertTrue(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("192.0.2.1")));
        assertTrue(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("240.0.0.1")));
        assertTrue(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("2001:db8::1")));
        assertTrue(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("fd00::1")));
        assertFalse(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(SafeImageFetcher.isForbiddenAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }

    @Test
    void permitsConfiguredProxyFakeIpButNeverArbitraryOrDirectIpUrls() throws Exception {
        Config config = ConfigUtil.copy(ConfigUtil.CONFIG);
        InetAddress fakeIp = InetAddress.getByName("198.18.0.23");

        assertDoesNotThrow(() -> SafeImageFetcher.validateResolvedAddresses(
                "mikanani.me", new InetAddress[]{fakeIp}, config));
        assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.validateResolvedAddresses(
                "unlisted.example", new InetAddress[]{fakeIp}, config));
        assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.validateResolvedAddresses(
                "198.18.0.23", new InetAddress[]{fakeIp}, config));
        assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.validateResolvedAddresses(
                "images.example.test", new InetAddress[]{InetAddress.getByName("127.0.0.1")}, config));
    }

    @Test
    void enforcesRedirectLimitAndDeclaredBodyLimit() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger redirects = new AtomicInteger();
        server.createContext("/loop", exchange -> {
            redirects.incrementAndGet();
            redirect(exchange, "/loop");
        });
        server.createContext("/oversized", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, SafeImageFetcher.MAX_BYTES + 1);
            exchange.close();
        });
        server.start();
        try {
            System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
            Config allowLocal = ConfigUtil.copy(ConfigUtil.CONFIG);
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            assertThrows(IllegalStateException.class,
                    () -> SafeImageFetcher.fetch(origin + "/loop", allowLocal));
            assertTrue(redirects.get() <= SafeImageFetcher.MAX_REDIRECTS + 1);
            assertThrows(IllegalStateException.class,
                    () -> SafeImageFetcher.fetch(origin + "/oversized", allowLocal));
        } finally {
            System.clearProperty(PRIVATE_ALLOWLIST);
            server.stop(0);
        }
    }

    @Test
    void rejectsUrlCredentialsBeforeConnecting() {
        System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
        try {
            Config config = ConfigUtil.copy(ConfigUtil.CONFIG);
            assertThrows(IllegalArgumentException.class,
                    () -> SafeImageFetcher.fetch("http://user:password@127.0.0.1/image", config));
        } finally {
            System.clearProperty(PRIVATE_ALLOWLIST);
        }
    }

    @Test
    void routesConfiguredImageDomainsThroughTheApplicationProxy() throws Exception {
        byte[] image = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger proxyHits = new AtomicInteger();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/image", exchange -> {
            targetHits.incrementAndGet();
            respond(exchange, 200, "image/png", image);
        });
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            proxyHits.incrementAndGet();
            respond(exchange, 200, "image/png", image);
        });
        target.start();
        proxy.start();
        try {
            System.setProperty(PRIVATE_ALLOWLIST, "127.0.0.1");
            Config config = ConfigUtil.copy(ConfigUtil.CONFIG)
                    .setProxy(true)
                    .setProxyHost("127.0.0.1")
                    .setProxyPort(proxy.getAddress().getPort())
                    .setProxyList("127.0.0.1");
            SafeImageFetcher.FetchedImage fetched = SafeImageFetcher.fetch(
                    "http://127.0.0.1:" + target.getAddress().getPort() + "/image", config);
            assertArrayEquals(image, fetched.bytes());
            assertTrue(proxyHits.get() > 0);
            assertTrue(targetHits.get() == 0);
        } finally {
            System.clearProperty(PRIVATE_ALLOWLIST);
            proxy.stop(0);
            target.stop(0);
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
