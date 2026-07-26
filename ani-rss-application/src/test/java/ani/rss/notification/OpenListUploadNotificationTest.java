package ani.rss.notification;

import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationStatusEnum;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenListUploadNotificationTest {
    private HttpServer server;
    private volatile int status;
    private volatile String body;

    @BeforeEach
    void setUp() throws Exception {
        status = 200;
        body = "{\"code\":200,\"message\":\"ok\"}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/me", this::respond);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void connectionTestRequiresBothHttpAndApplicationSuccess() {
        OpenListUploadNotification notification = new OpenListUploadNotification();
        NotificationConfig config = config();

        assertDoesNotThrow(() -> notification.test(
                config, new Ani(), "", NotificationStatusEnum.DOWNLOAD_END));

        body = "{\"code\":500,\"message\":\"rejected\"}";
        assertThrows(IllegalStateException.class, () -> notification.test(
                config, new Ani(), "", NotificationStatusEnum.DOWNLOAD_END));

        status = 503;
        body = "{\"code\":200,\"message\":\"misleading\"}";
        assertThrows(IllegalStateException.class, () -> notification.test(
                config, new Ani(), "", NotificationStatusEnum.DOWNLOAD_END));
    }

    @Test
    void malformedSuccessBodyIsRejected() {
        body = "not-json";
        assertThrows(IllegalStateException.class, () -> new OpenListUploadNotification().test(
                config(), new Ani(), "", NotificationStatusEnum.DOWNLOAD_END));
    }

    private NotificationConfig config() {
        return new NotificationConfig()
                .setStatusList(List.of(NotificationStatusEnum.DOWNLOAD_END))
                .setOpenListUploadHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setOpenListUploadApiKey("test-key");
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
