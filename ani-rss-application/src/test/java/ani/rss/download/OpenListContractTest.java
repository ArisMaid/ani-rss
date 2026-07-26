package ani.rss.download;

import ani.rss.entity.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenListContractTest {
    private HttpServer server;
    private volatile int httpStatus;
    private volatile int applicationCode;

    @BeforeEach
    void startServer() throws IOException {
        httpStatus = 200;
        applicationCode = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void rejectsApplicationFailureEvenWhenHttpSucceeds() {
        applicationCode = 500;
        OpenList client = new OpenList(config());

        assertThrows(DownloaderOperationException.class,
                () -> client.fsMove("/source", "/target", List.of("episode.mkv")));
        assertFalse(client.login(true, config()));

        DownloaderResult<Void> result = new DownloaderClient(client, config()).connect(true);
        assertEquals("OPENLIST_APPLICATION_REJECTED", result.errorCode());
        assertFalse(result.retryable());
    }

    @Test
    void rejectsHttpFailureBeforeParsingBusinessResponse() {
        httpStatus = 503;
        OpenList client = new OpenList(config());

        assertThrows(DownloaderOperationException.class,
                () -> client.fsRemove("/source", List.of("episode.mkv")));

        DownloaderResult<Void> result = new DownloaderClient(client, config()).connect(true);
        assertEquals("OPENLIST_HTTP_503", result.errorCode());
        assertTrue(result.retryable());
    }

    private Config config() {
        return new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("token")
                .setDownloadPathTemplate("/downloads")
                .setProvider("provider")
                .setProxy(false);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = "{\"code\":" + applicationCode + ",\"message\":\"result\",\"data\":{}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
