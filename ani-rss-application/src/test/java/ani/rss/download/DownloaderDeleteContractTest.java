package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.bean.BeanUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderDeleteContractTest {
    private final Config original = ConfigUtil.copy(ConfigUtil.CONFIG);
    private final List<String> requests = new ArrayList<>();
    private HttpServer server;
    private int qbAddStatus = 200;
    private String qbInfoResponse = "[]";

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/api/v2/torrents/add")) {
                respond(exchange, qbAddStatus, qbAddStatus == 409 ? "Fails." : "Ok.");
                return;
            }
            if (path.endsWith("/api/v2/torrents/info")) {
                respond(exchange, 200, qbInfoResponse);
                return;
            }
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

    @Test
    void qbittorrentReportsTheCanonicalTorrentHashInsteadOfAnOpaqueCacheFilename() throws Exception {
        Path torrent = tempDir.resolve("rss-enclosure-key.torrent");
        Files.write(torrent, torrentBytes());
        String actualHash = TorrentUtil.getInfoHash(torrent.toFile());

        Config config = config("submitted")
                .setQbUseDownloadPath(false)
                .setRatioLimit(-2)
                .setSeedingTimeLimit(-2)
                .setInactiveSeedingTimeLimit(-2)
                .setRename(false)
                .setUpLimit(0L)
                .setDlLimit(0L);
        Item item = new Item()
                .setInfoHash("rss-enclosure-key")
                .setReName("episode")
                .setMaster(true)
                .setSubgroup("test");
        Ani ani = new Ani().setId("subscription").setCustomTagsEnable(false);

        DownloaderResult<Void> result = new qBittorrent(new DownloadService(), config)
                .downloadResult(ani, item, "/downloads", torrent.toFile());

        assertTrue(result.isSuccess());
        assertEquals(actualHash, result.remoteTaskId());
        assertNotEquals(item.getInfoHash(), result.remoteTaskId());
    }

    @Test
    void qbittorrentRecoversAVerifiedDuplicateSubmission() throws Exception {
        Path torrent = tempDir.resolve("duplicate.torrent");
        Files.write(torrent, torrentBytes());
        String hash = TorrentUtil.getInfoHash(torrent.toFile());
        qbAddStatus = 409;
        qbInfoResponse = torrentInfo(hash, "ani-rss,subscription,test", "ani-rss", "/downloads");

        DownloaderResult<Void> result = new qBittorrent(new DownloadService(), duplicateConfig())
                .downloadResult(duplicateAni(), duplicateItem(), "/downloads", torrent.toFile());

        assertTrue(result.isSuccess());
        assertEquals(hash, result.remoteTaskId());
    }

    @Test
    void qbittorrentRejectsAnUnverifiedDuplicateSubmission() throws Exception {
        Path torrent = tempDir.resolve("unowned-duplicate.torrent");
        Files.write(torrent, torrentBytes());
        String hash = TorrentUtil.getInfoHash(torrent.toFile());
        qbAddStatus = 409;
        qbInfoResponse = torrentInfo(hash, "external", "external", "/downloads");

        DownloaderResult<Void> result = new qBittorrent(new DownloadService(), duplicateConfig())
                .downloadResult(duplicateAni(), duplicateItem(), "/downloads", torrent.toFile());

        assertFalse(result.isSuccess());
        assertEquals("QBITTORRENT_DUPLICATE_UNOWNED", result.errorCode());
    }

    private Config duplicateConfig() {
        return config("submitted")
                .setQbUseDownloadPath(false)
                .setRatioLimit(-2)
                .setSeedingTimeLimit(-2)
                .setInactiveSeedingTimeLimit(-2)
                .setRename(false)
                .setUpLimit(0L)
                .setDlLimit(0L);
    }

    private static Ani duplicateAni() {
        return new Ani().setId("subscription").setCustomTagsEnable(false);
    }

    private static Item duplicateItem() {
        return new Item()
                .setInfoHash("rss-enclosure-key")
                .setReName("episode")
                .setMaster(true)
                .setSubgroup("test");
    }

    private static String torrentInfo(String hash, String tags, String category, String savePath) {
        return "[{\"hash\":\"" + hash + "\",\"name\":\"episode\",\"tags\":\"" + tags
                + "\",\"category\":\"" + category + "\",\"save_path\":\"" + savePath
                + "\",\"state\":\"downloading\",\"completed\":0,\"size\":1}]";
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
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static byte[] torrentBytes() throws Exception {
        ByteArrayOutputStream torrent = new ByteArrayOutputStream();
        torrent.write('d');
        bytes(torrent, "announce");
        bytes(torrent, "");
        bytes(torrent, "info");
        torrent.write('d');
        bytes(torrent, "length");
        integer(torrent, 1);
        bytes(torrent, "name");
        bytes(torrent, "test.mkv");
        bytes(torrent, "piece length");
        integer(torrent, 16 * 1024);
        bytes(torrent, "pieces");
        bytes(torrent, MessageDigest.getInstance("SHA-1").digest(new byte[]{'x'}));
        bytes(torrent, "source");
        bytes(torrent, "ani-rss-test");
        torrent.write('e');
        torrent.write('e');
        return torrent.toByteArray();
    }

    private static void integer(ByteArrayOutputStream output, int value) {
        output.writeBytes(("i" + value + "e").getBytes(StandardCharsets.US_ASCII));
    }

    private static void bytes(ByteArrayOutputStream output, String value) {
        bytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(ByteArrayOutputStream output, byte[] value) {
        output.writeBytes((value.length + ":").getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(value);
    }
}
