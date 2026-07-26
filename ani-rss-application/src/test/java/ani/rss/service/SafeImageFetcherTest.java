package ani.rss.service;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeImageFetcherTest {
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
            Config allowLocal = ConfigUtil.copy(ConfigUtil.CONFIG)
                    .setImagePrivateAllowlist("127.0.0.1");
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
            Config allowHostnameOnly = ConfigUtil.copy(ConfigUtil.CONFIG)
                    .setImagePrivateAllowlist("localhost");
            assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.fetch(
                    "http://localhost:" + port + "/redirect-to-address", allowHostnameOnly));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsPrivateHostWithoutExplicitAllowlist() {
        Config config = ConfigUtil.copy(ConfigUtil.CONFIG).setImagePrivateAllowlist("");
        assertThrows(IllegalArgumentException.class, () -> SafeImageFetcher.fetch(
                "http://127.0.0.1:1/image", config));
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
