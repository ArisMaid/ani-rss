package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransmissionContractTest {
    private HttpServer server;
    private List<String> requests;
    private AtomicInteger calls;
    private volatile boolean legacyMode;
    private volatile boolean authFailure;
    private volatile int serverFailures;

    @BeforeEach
    void startServer() throws IOException {
        requests = new ArrayList<>();
        calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/transmission/rpc", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void negotiatesSessionAndFallsBackToLegacyDialect() {
        legacyMode = true;
        Transmission client = new Transmission();
        Config config = config();

        assertTrue(client.login(true, config));
        assertTrue(client.delete(new TorrentsInfo().setId("legacy-id"), false));

        assertEquals(4, calls.get());
        assertTrue(requests.get(0).contains("\"jsonrpc\":\"2.0\""));
        assertTrue(requests.get(1).contains("\"jsonrpc\":\"2.0\""));
        assertTrue(requests.get(2).contains("\"method\":\"session-get\""));
        assertTrue(requests.get(3).contains("\"method\":\"torrent-remove\""));
        assertTrue(requests.get(3).contains("\"delete-local-data\":false"));
    }

    @Test
    void keepsJsonRpc2DialectAfterSessionNegotiation() {
        legacyMode = false;
        Transmission client = new Transmission();
        Config config = config();

        assertTrue(client.login(true, config));
        assertTrue(client.delete(new TorrentsInfo().setId("modern-id"), true));

        assertEquals(3, calls.get());
        assertTrue(requests.get(0).contains("\"jsonrpc\":\"2.0\""));
        assertTrue(requests.get(1).contains("\"jsonrpc\":\"2.0\""));
        assertTrue(requests.get(2).contains("\"method\":\"torrent_remove\""));
        assertTrue(requests.get(2).contains("\"delete_local_data\":false"));
    }

    @Test
    void doesNotRetryOrFallbackOnAuthenticationFailure() {
        authFailure = true;
        Transmission client = new Transmission();

        DownloaderResult<Void> result = new DownloaderClient(client, config()).connect(true);
        assertTrue(!result.isSuccess());
        assertEquals("TRANSMISSION_AUTHENTICATION_FAILED", result.errorCode());
        assertTrue(!result.retryable());
        assertEquals(1, calls.get());
    }

    @Test
    void retriesBoundedServerFailures() {
        serverFailures = 2;
        Transmission client = new Transmission();

        assertTrue(client.login(true, config()));
        assertEquals(3, calls.get());
    }

    private Config config() {
        return new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolUsername("user")
                .setDownloadToolPassword("password")
                .setProxy(false);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        int call = calls.incrementAndGet();
        if (authFailure) {
            respond(exchange, 401, "{}");
            return;
        }
        if (call <= serverFailures) {
            respond(exchange, 503, "{}");
            return;
        }
        if (serverFailures > 0) {
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            return;
        }
        if (call == 1) {
            exchange.getResponseHeaders().add("X-Transmission-Session-Id", "session-1");
            respond(exchange, 409, "{}");
            return;
        }
        boolean modern = body.contains("\"jsonrpc\":\"2.0\"");
        if (call == 2 && modern && body.contains("\"method\":\"session_get\"")) {
            if (legacyMode) {
                respond(exchange, 200, "{\"arguments\":{},\"result\":\"success\"}");
            } else {
                exchange.getResponseHeaders().add("X-Transmission-Session-Id", "session-2");
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            }
            return;
        }
        if (modern) {
            respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{}}");
            return;
        }
        respond(exchange, 200, "{\"arguments\":{},\"result\":\"success\"}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
