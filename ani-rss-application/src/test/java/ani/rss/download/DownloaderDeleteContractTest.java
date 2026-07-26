package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderDeleteContractTest {
    private final Config original = ConfigUtil.copy(ConfigUtil.CONFIG);
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = exchange.getRequestURI().getPath().contains("jsonrpc")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"OK\"}"
                    : "Ok.";
            respond(exchange, response);
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        BeanUtil.copyProperties(original, ConfigUtil.CONFIG);
    }

    @Test
    void qbittorrentNeverDeletesFilesDirectly() {
        Config config = config("submitted");
        qBittorrent client = new qBittorrent(new DownloadService(), config);

        assertTrue(client.delete(new TorrentsInfo().setHash("hash"), true));
        assertTrue(requests.get(0).contains("deleteFiles=false"));
    }

    @Test
    void aria2SeparatesActiveRemovalFromResultCleanupAndUsesSnapshotToken() {
        ConfigUtil.CONFIG.setDownloadToolPassword("global-secret");
        Aria2 client = new Aria2(config("submitted-secret"));

        assertTrue(client.delete(new TorrentsInfo().setId("active")
                .setState(TorrentsStateEnum.downloading), true));
        assertTrue(client.delete(new TorrentsInfo().setId("done")
                .setState(TorrentsStateEnum.stoppedUP), true));

        JsonObject active = JsonParser.parseString(requests.get(0)).getAsJsonObject();
        JsonObject done = JsonParser.parseString(requests.get(1)).getAsJsonObject();
        assertTrue("aria2.remove".equals(active.get("method").getAsString()));
        assertTrue("aria2.removeDownloadResult".equals(done.get("method").getAsString()));
        assertTrue(requests.stream()
                .map(JsonParser::parseString)
                .allMatch(json -> "token:submitted-secret".equals(json.getAsJsonObject()
                        .getAsJsonArray("params").get(0).getAsString())));
    }

    private Config config(String password) {
        return new Config()
                .setDownloadToolHost("http://127.0.0.1:" + server.getAddress().getPort())
                .setDownloadToolUsername("user")
                .setDownloadToolPassword(password)
                .setProxy(false)
                .setUuid("request-id");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
