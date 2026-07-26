package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.service.DownloadService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QBittorrentAuthenticationContractTest {
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private volatile int versionStatus;

    @BeforeEach
    void startServer() throws IOException {
        versionStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void apiKeyUsesBearerForConnectionAndEveryApiRequest() {
        qBittorrent client = new qBittorrent(new DownloadService(), config("qbt_local-test"));

        assertTrue(client.login(true, new Config()));
        assertTrue(client.getTorrentsInfos().isEmpty());
        assertTrue(client.delete(new TorrentsInfo().setHash("hash"), false));

        assertFalse(requests.isEmpty());
        assertTrue(requests.stream().noneMatch(request ->
                request.path().equals("/api/v2/auth/login")));
        assertTrue(requests.stream().allMatch(request ->
                "Bearer qbt_local-test".equals(request.authorization())));
    }

    @Test
    void rejectedApiKeyProducesStableNonRetryableAuthenticationFailure() {
        versionStatus = 401;
        Config config = config("qbt_rejected");
        DownloaderResult<Void> result = new DownloaderClient(
                new qBittorrent(new DownloadService(), config), config).connect(true);

        assertFalse(result.isSuccess());
        assertFalse(result.retryable());
        assertEquals("QBITTORRENT_AUTHENTICATION_FAILED", result.errorCode());
    }

    private Config config(String password) {
        return new Config()
                .setDownloadToolType("qBittorrent")
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolUsername("")
                .setDownloadToolPassword(password)
                .setProxy(false);
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(path,
                exchange.getRequestHeaders().getFirst("Authorization")));
        if (path.equals("/api/v2/app/version")) {
            respond(exchange, versionStatus, versionStatus == 200 ? "5.2.0" : "Unauthorized");
            return;
        }
        if (path.equals("/api/v2/torrents/info")) {
            respond(exchange, 200, "[]");
            return;
        }
        respond(exchange, 200, "Ok.");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private record Request(String path, String authorization) {
    }
}
