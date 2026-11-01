package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.service.DownloadService;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.torrent.*;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeDownloaderContractTest {
    private HttpServer server; private final List<String> bodies=new ArrayList<>(); private String response="Ok.";
    @BeforeEach void before() throws Exception { server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.createContext("/",exchange->{ String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); bodies.add(body); byte[] bytes=response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); try(var out=exchange.getResponseBody()) { out.write(bytes); } }); server.start(); }
    @AfterEach void after() { server.stop(0); }
    private Config config() { return new Config().setProxy(false).setDownloadToolHost("http://127.0.0.1:"+server.getAddress().getPort()).setDownloadToolUsername("test").setDownloadToolPassword("test-only").setRatioLimit(-2).setSeedingTimeLimit(-2); }
    private DownloadSubmission submission() { return new DownloadSubmission(DownloadSubmission.OwnerKind.UPGRADE_TARGET,"target","operation","0123456789012345678901234567890123456789","/staging/operation","display","magnet:?xt=urn:btih:0123456789012345678901234567890123456789",null,Set.of("1")); }
    @Test void qbitRawSubmissionDoesNotRenameAndDeletionNeverDeletesPayload() {
        var client=new qBittorrent(new DownloadService(),config()); assertTrue(client.submit(submission()).isSuccess()); assertTrue(bodies.get(0).contains("AUTOMATIC_UPGRADE")); assertFalse(bodies.get(0).contains("rename="));
        assertTrue(client.delete(new TorrentsInfo().setHash(submission().infoHash()),false)); assertTrue(bodies.get(1).contains("deleteFiles=false"));
    }
    @Test void ariaRawSubmissionAcceptsMagnetsWithoutFakeSubscription() {
        response="{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":\"payload-gid\"}";
        var result=new Aria2(config()).submit(submission()); assertTrue(result.isSuccess()); assertEquals("payload-gid",result.remoteTaskId()); assertTrue(bodies.get(0).contains("aria2.addUri")); assertTrue(bodies.get(0).contains("/staging/operation"));
    }
    @Test void ariaInventoryIncludesTasksBeyondTheFirstWaitingPage() {
        List<Integer> offsets = new ArrayList<>();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var page = new JsonArray();
            if ("aria2.tellWaiting".equals(request.get("method").getAsString())) {
                int offset = request.getAsJsonArray("params").get(1).getAsInt();
                offsets.add(offset);
                for (int i = offset; i < Math.min(offset + 1000, 1001); i++) {
                    JsonObject task = new JsonObject();
                    task.addProperty("gid", "task-" + i);
                    task.addProperty("dir", "/staging");
                    page.add(task);
                }
            }
            byte[] bytes = ("{\"result\":" + page + "}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        var tasks = new Aria2(config()).inventory().value();
        assertEquals(List.of(0, 1000), offsets);
        assertEquals(1001, tasks.size());
        assertEquals("task-1000", tasks.get(1000).getId());
    }
    @Test void ariaMetadataRecordCannotPretendToBeFinishedVideoTransfer() {
        var metadata=GsonStatic.fromJson("{\"gid\":\"metadata\",\"status\":\"complete\",\"dir\":\"/staging\",\"completedLength\":100,\"totalLength\":100,\"files\":[]}",Aria2TorrentsInfo.Torrent.class).toTorrentsInfo(); assertEquals("ARIA2_METADATA",metadata.getCategory());
    }
    @Test void transmissionRawSubmissionAndNoDeleteRemovalUseRpcContract() {
        server.removeContext("/"); server.createContext("/",exchange->{ String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); bodies.add(body); String result=body.contains("torrent_add")?"{\"torrent_added\":{\"id\":7,\"hashString\":\"0123456789012345678901234567890123456789\"}}":"{}"; byte[] bytes=("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":"+result+"}").getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length); try(var out=exchange.getResponseBody()) { out.write(bytes); } });
        var client=new Transmission(config()); assertTrue(client.submit(submission()).isSuccess()); assertTrue(client.delete(new TorrentsInfo().setId("7"),false)); assertTrue(bodies.stream().anyMatch(s->s.contains("AUTOMATIC_UPGRADE"))); assertTrue(bodies.stream().anyMatch(s->s.contains("\"delete_local_data\":false")));
    }
}
