package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.NotificationUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.google.gson.JsonObject;
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
import java.util.Date;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class AstrBotNotificationTest {
    @TempDir
    Path configDir;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String originalConfig;
    private volatile int messageStatus = 200;
    private volatile String messageResponse = "{\"status\":\"OK\"}";

    @BeforeEach
    void startServer() throws IOException {
        originalConfig = System.getProperty("CONFIG");
        System.setProperty("CONFIG", configDir.toString());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/file", exchange ->
                respond(exchange, 200, "{\"data\":{\"attachment_id\":\"poster-42\"}}"));
        server.createContext("/api/v1/im/message", exchange -> respond(exchange, messageStatus, messageResponse));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        if (originalConfig == null) {
            System.clearProperty("CONFIG");
        } else {
            System.setProperty("CONFIG", originalConfig);
        }
    }

    @Test
    void sendsRenderedTextWhenImagesAreDisabledOrTheCoverIsMissing() {
        NotificationConfig config = config().setAstrBotImage(false);
        assertTrue(send(config, "poster.jpg"));
        config.setAstrBotImage(true);
        assertTrue(send(config, "missing.jpg"));
        assertTrue(config.getAstrBotImage());

        assertEquals(2, requests.size());
        for (Request request : requests) {
            JsonObject body = assertMessageRequest(request);
            assertEquals("已下载 第一集", body.get("message").getAsString());
        }
    }

    @Test
    void uploadsTheLocalCoverBeforeSendingImageAndTextTogether() throws IOException {
        Path cover = configDir.resolve("files/cache/poster.jpg");
        Files.createDirectories(cover.getParent());
        Files.writeString(cover, "image-bytes");

        assertTrue(send(config(), "cache/poster.jpg"));

        assertEquals(2, requests.size());
        Request upload = requests.get(0);
        assertEquals("/api/v1/file", upload.path());
        assertEquals("POST", upload.method());
        assertEquals("Bearer test-key", upload.authorization());
        assertTrue(upload.contentType().startsWith("multipart/form-data; boundary="));
        assertTrue(upload.body().contains("name=\"file\"; filename=\"poster.jpg\""));
        assertTrue(upload.body().contains("image-bytes"));

        JsonObject body = assertMessageRequest(requests.get(1));
        var segments = body.getAsJsonArray("message");
        assertEquals(JsonParser.parseString("""
                [{"type":"image","attachment_id":"poster-42"},{"type":"plain","text":"已下载 第一集"}]
                """), segments);
        assertEquals(AstrBotNotification.class, NotificationUtil.NOTIFICATION_MAP.get(NotificationTypeEnum.ASTRBOT));
    }

    @Test
    void rejectsAnOutOfRootCoverBeforeSendingAnyRequest() throws IOException {
        Files.writeString(configDir.resolve("private.jpg"), "private data");
        assertThrows(IllegalArgumentException.class, () -> send(config(), "../private.jpg"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void checksHttpStatusBeforeDecodingJsonAndRejectsMalformedOrFailedMessages() {
        messageStatus = 401;
        messageResponse = "unauthorized";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> send(config(), "")).getMessage().contains("401"));

        messageStatus = 200;
        messageResponse = "{";
        assertTrue(assertThrows(IllegalStateException.class, () -> send(config(), "")).getMessage().contains("JSON"));

        messageResponse = "{\"status\":\"failed\"}";
        assertThrows(IllegalArgumentException.class, () -> send(config(), ""));
    }

    private NotificationConfig config() {
        return NotificationConfig.createNotificationConfig()
                .setNotificationType(NotificationTypeEnum.ASTRBOT)
                .setNotificationTemplate("已下载 ${text}")
                .setAstrBotHost("  http://127.0.0.1:" + server.getAddress().getPort() + "///  ")
                .setAstrBotApiKey(" test-key ")
                .setAstrBotUmo(" test-platform:GroupMessage:123 ");
    }

    private boolean send(NotificationConfig config, String cover) {
        Ani ani = new Ani().setTitle("测试订阅").setCover(cover).setSeason(1).setReleaseDate(new Date());
        DownloadService downloads = mock(DownloadService.class);
        when(downloads.getDownloadPath(any(Ani.class))).thenReturn("/downloads");
        try (var spring = mockStatic(SpringUtil.class)) {
            spring.when(() -> SpringUtil.getBean(DownloadService.class)).thenReturn(downloads);
            return new AstrBotNotification().send(config, ani, "第一集", NotificationStatusEnum.DOWNLOAD_START);
        }
    }

    private static JsonObject assertMessageRequest(Request request) {
        assertEquals("/api/v1/im/message", request.path());
        assertEquals("POST", request.method());
        assertEquals("Bearer test-key", request.authorization());
        assertTrue(request.contentType().startsWith("application/json"));
        JsonObject body = JsonParser.parseString(request.body()).getAsJsonObject();
        assertEquals("test-platform:GroupMessage:123", body.get("umo").getAsString());
        return body;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            requests.add(new Request(exchange.getRequestURI().getPath(), exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private record Request(String path, String method, String authorization, String contentType, String body) {
    }
}
