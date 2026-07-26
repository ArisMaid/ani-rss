package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.service.DownloadService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderFailureContractTest {
    private HttpServer server;
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void startServer() throws IOException {
        status = 200;
        body = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void qbittorrentRetriesServerFailureButNotAuthenticationRejection() {
        status = 503;
        Config config = baseConfig().setDownloadToolUsername("user");
        DownloaderClient serverFailure = new DownloaderClient(
                new qBittorrent(new DownloadService(), config), config);

        DownloaderResult<Void> unavailable = serverFailure.connect(true);
        assertEquals("QBITTORRENT_HTTP_503", unavailable.errorCode());
        assertTrue(unavailable.retryable());

        status = 200;
        body = "Fails.";
        Config authConfig = baseConfig().setDownloadToolUsername("user");
        DownloaderResult<Void> rejected = new DownloaderClient(
                new qBittorrent(new DownloadService(), authConfig), authConfig).connect(true);
        assertEquals("QBITTORRENT_AUTHENTICATION_FAILED", rejected.errorCode());
        assertFalse(rejected.retryable());
    }

    @Test
    void aria2ApplicationErrorIsNotRetryable() {
        body = "{\"jsonrpc\":\"2.0\",\"id\":\"test\",\"error\":{\"code\":1}}";
        Config config = baseConfig().setUuid("test");

        DownloaderResult<Void> result = new DownloaderClient(new Aria2(config), config).connect(true);

        assertEquals("ARIA2_APPLICATION_REJECTED", result.errorCode());
        assertFalse(result.retryable());
    }

    private Config baseConfig() {
        return new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("secret")
                .setProxy(false);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
