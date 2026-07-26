package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderConfigurationIsolationTest {
    private final Config original = ConfigUtil.copy(ConfigUtil.CONFIG);
    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void restoreGlobalConfiguration() {
        BeanUtil.copyProperties(original, ConfigUtil.CONFIG);
        servers.forEach(server -> server.stop(0));
    }

    @Test
    void concurrentConnectionTestsUseOnlySubmittedConfigurations() throws Exception {
        ConfigUtil.CONFIG.setDownloadToolHost("http://127.0.0.1:1").setProxy(false);

        for (String type : List.of("qBittorrent", "Transmission", "Aria2", "OpenList")) {
            FakeEndpoint first = endpoint();
            FakeEndpoint second = endpoint();
            Config firstConfig = config(type, first.port(), "first-password");
            Config secondConfig = config(type, second.port(), "second-password");

            CompletableFuture<Boolean> firstResult = CompletableFuture.supplyAsync(() ->
                    DownloaderClientFactory.createTestClient(firstConfig).connect(true).isSuccess());
            CompletableFuture<Boolean> secondResult = CompletableFuture.supplyAsync(() ->
                    DownloaderClientFactory.createTestClient(secondConfig).connect(true).isSuccess());

            assertTrue(firstResult.get());
            assertTrue(secondResult.get());
            assertEquals(1, first.requests().get(), type + " first endpoint");
            assertEquals(1, second.requests().get(), type + " second endpoint");
        }
    }

    @Test
    void qbittorrentTestClientCannotReplaceActiveClientSessionCookie() throws Exception {
        List<String> loginBodies = java.util.Collections.synchronizedList(new ArrayList<>());
        List<String> versionCookies = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api/v2/auth/login")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                loginBodies.add(body);
                String sid = body.contains("active-password") ? "active" : "test";
                exchange.getResponseHeaders().add("Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly");
                respond(exchange, "Ok.");
                return;
            }
            if (path.equals("/api/v2/app/version")) {
                versionCookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
                respond(exchange, "5.2.0");
                return;
            }
            respond(exchange, "Not Found");
        });
        server.start();
        servers.add(server);

        Config activeConfig = config("qBittorrent", server.getAddress().getPort(), "active-password");
        Config testConfig = config("qBittorrent", server.getAddress().getPort(), "test-password");
        DownloaderClient active = DownloaderClientFactory.createClient(activeConfig);
        DownloaderClient temporary = DownloaderClientFactory.createTestClient(testConfig);

        assertTrue(active.connect(true).isSuccess());
        assertTrue(temporary.connect(true).isSuccess());
        assertTrue(active.connect(false).isSuccess());
        assertTrue(temporary.connect(false).isSuccess());

        assertEquals(2, loginBodies.size());
        assertEquals(2, versionCookies.size());
        assertTrue(versionCookies.get(0).contains("active"));
        assertTrue(versionCookies.get(1).contains("test"));
    }

    private FakeEndpoint endpoint() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.equals("/api/v2/auth/login")) {
                body = "Ok.";
            } else if (path.equals("/api/me")) {
                body = "{\"code\":200,\"message\":\"success\",\"data\":{}}";
            } else {
                body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}";
            }
            respond(exchange, body);
        });
        server.start();
        servers.add(server);
        return new FakeEndpoint(server.getAddress().getPort(), requests);
    }

    private static Config config(String type, int port, String password) {
        return new Config()
                .setDownloadToolType(type)
                .setDownloadToolHost("http://127.0.0.1:" + port)
                .setDownloadToolUsername("user")
                .setDownloadToolPassword(password)
                .setDownloadPathTemplate("/downloads/${title}")
                .setProvider("provider")
                .setProxy(false);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record FakeEndpoint(int port, AtomicInteger requests) {
    }
}
