package ani.rss.notification;

import ani.rss.entity.NotificationConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramNotificationTest {
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bottest-token/getUpdates", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void keepsLargeChatIdsAndDeduplicatesUpdates() {
        NotificationConfig config = new NotificationConfig()
                .setTelegramBotToken("test-token")
                .setTelegramApiHost("http://127.0.0.1:" + server.getAddress().getPort());

        List<TelegramNotification.Message.Chat> chats = TelegramNotification.getUpdates(config);

        assertEquals(2, chats.size());
        assertEquals("-1001234567890", chats.get(0).getId());
        assertEquals("-1001234567890", chats.get(0).getUsername());
        assertEquals("1234567890123", chats.get(1).getId());
        assertEquals("channel-user", chats.get(1).getUsername());
    }

    @Test
    void returnsEmptyListWhenTelegramOmitsResult() throws IOException {
        server.removeContext("/bottest-token/getUpdates");
        server.createContext("/bottest-token/getUpdates", exchange -> respond(exchange, "{\"ok\":false}"));

        NotificationConfig config = new NotificationConfig()
                .setTelegramBotToken("test-token")
                .setTelegramApiHost("http://127.0.0.1:" + server.getAddress().getPort());

        assertTrue(TelegramNotification.getUpdates(config).isEmpty());
    }

    private void handle(HttpExchange exchange) throws IOException {
        respond(exchange, """
                {"ok":true,"result":[
                  {"update_id":1,"message":{"chat":{"id":-1001234567890,"type":"supergroup"}}},
                  {"update_id":2,"message":{"chat":{"id":-1001234567890,"type":"supergroup","first_name":"Duplicate"}}},
                  {"update_id":3,"my_chat_member":{"chat":{"id":1234567890123,"type":"channel","username":"channel-user"}}}
                ]}
                """);
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
