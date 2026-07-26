package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenListWorkflowTest {
    @TempDir
    Path tempDir;

    private HttpServer server;
    private List<String> paths;
    private volatile String applicationFailurePath;
    private volatile boolean renamed;
    private volatile int moveFailures;
    private volatile int moveCalls;

    @BeforeEach
    void startServer() throws IOException {
        paths = Collections.synchronizedList(new ArrayList<>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void applicationFailureAfterSubmissionStopsBeforeRenameMoveOrRemove() throws Exception {
        applicationFailurePath = "/api/task/offline_download/info";
        DownloaderResult<Void> result = client().download(ani(), item(), "/downloads", torrent());

        assertFalse(result.isSuccess());
        assertEquals("OPENLIST_APPLICATION_REJECTED", result.errorCode());
        assertEquals("task-1", result.remoteTaskId());
        assertFalse(result.retryable());
        assertFalse(paths.contains("/api/fs/batch_rename"));
        assertFalse(paths.contains("/api/fs/move"));
        assertFalse(paths.contains("/api/fs/remove"));
    }

    @Test
    void successfulWorkflowReturnsTaskIdAndNeverDeletesRemoteFiles() throws Exception {
        DownloaderResult<Void> result = client().download(ani(), item(), "/downloads", torrent());

        assertTrue(result.isSuccess());
        assertEquals("task-1", result.remoteTaskId());
        assertTrue(paths.contains("/api/fs/batch_rename"));
        assertTrue(paths.contains("/api/fs/move"));
        assertFalse(paths.contains("/api/fs/remove"));
    }

    @Test
    void retryAfterMoveServerFailureResumesSameTaskWithoutRepeatingRename() throws Exception {
        moveFailures = 1;
        DownloaderClient client = client();

        DownloaderResult<Void> first = client.download(ani(), item(), "/downloads", torrent());
        DownloaderResult<Void> second = client.download(ani(), item(), "/downloads", torrent());

        assertFalse(first.isSuccess());
        assertTrue(first.retryable());
        assertEquals("OPENLIST_HTTP_503", first.errorCode());
        assertEquals("task-1", first.remoteTaskId());
        assertTrue(second.isSuccess());
        assertEquals(1, Collections.frequency(paths, "/api/fs/add_offline_download"));
        assertEquals(1, Collections.frequency(paths, "/api/fs/batch_rename"));
        assertEquals(2, moveCalls);
        assertFalse(paths.contains("/api/fs/remove"));
    }

    private DownloaderClient client() {
        Config config = config();
        return new DownloaderClient(new OpenList(config), config);
    }

    private Config config() {
        return new Config()
                .setDownloadToolType("OpenList")
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolPassword("token")
                .setDownloadPathTemplate("/downloads")
                .setProvider("provider")
                .setOpenListDownloadTimeout(1)
                .setOpenListDownloadRetryNumber(0L)
                .setRename(true)
                .setDelete(false)
                .setNotificationConfigList(List.of())
                .setProxy(false);
    }

    private Ani ani() {
        return new Ani().setId("subscription").setOva(false).setMessage(false);
    }

    private Item item() {
        return new Item().setInfoHash("0123456789abcdef0123456789abcdef01234567")
                .setReName("Episode 01");
    }

    private java.io.File torrent() throws IOException {
        Path file = tempDir.resolve("task.txt");
        Files.writeString(file,
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                StandardCharsets.UTF_8);
        return file.toFile();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        paths.add(path);
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (path.equals(applicationFailurePath)) {
            respond(exchange, "{\"code\":500,\"message\":\"rejected\",\"data\":null}");
            return;
        }
        if ("/api/fs/move".equals(path)) {
            moveCalls++;
            if (moveFailures-- > 0) {
                respond(exchange, 503, "{\"code\":503,\"message\":\"unavailable\",\"data\":null}");
                return;
            }
        }

        String data = switch (path) {
            case "/api/task/offline_download/done", "/api/task/offline_download/undone" -> "[]";
            case "/api/fs/add_offline_download" -> "{\"tasks\":[{\"id\":\"task-1\"}]}";
            case "/api/task/offline_download/info" ->
                    "{\"id\":\"task-1\",\"name\":\"task\",\"state\":2}";
            case "/api/fs/list" -> listData(requestBody);
            case "/api/fs/batch_rename" -> {
                renamed = true;
                yield "{}";
            }
            default -> "{}";
        };
        respond(exchange, "{\"code\":200,\"message\":\"ok\",\"data\":" + data + "}");
    }

    private String listData(String requestBody) {
        String requestedPath = JsonParser.parseString(requestBody)
                .getAsJsonObject().get("path").getAsString();
        if ("/downloads".equals(requestedPath)) {
            return "{\"content\":[]}";
        }
        String name = renamed ? "Episode 01.mkv" : "source.mkv";
        return "{\"content\":[{\"name\":\"" + name +
                "\",\"size\":1024,\"is_dir\":false}]}";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
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
