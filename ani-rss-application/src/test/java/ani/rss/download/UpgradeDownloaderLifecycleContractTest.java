package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.service.DownloadService;
import ani.rss.upgrade.*;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

/** Exercises the real adapters and orchestration against isolated HTTP contracts. */
class UpgradeDownloaderLifecycleContractTest {
    private static final String HASH="0123456789012345678901234567890123456789";
    @TempDir Path temporary;
    private String previousConfig,kind;
    private Config original;
    private HttpServer server;
    private MockedStatic<TorrentUtil> torrents;
    private UpgradeRepository repository;
    private UpgradeDownloadService downloads;
    private Job job;
    private Release release;
    private volatile boolean present,finished,rejectStop;
    private volatile String remoteRoot="/staging",lastStop="",listedHash=HASH;
    private final AtomicInteger submissions=new AtomicInteger(),stopAttempts=new AtomicInteger();

    @BeforeEach void before() throws Exception {
        previousConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",temporary.resolve("config").toString()); DatabaseManager.close();
        original=ConfigUtil.snapshot(); server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String path=exchange.getRequestURI().getPath(); boolean stop=path.endsWith("/delete")||body.contains("torrent_remove")||body.contains("aria2.remove");
            if(stop) { lastStop=body; stopAttempts.incrementAndGet(); }
            int status=stop&&rejectStop?503:200;
            String result=status==503?"unavailable":respond(path,body);
            byte[] bytes=result.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length);
            try(var out=exchange.getResponseBody()) { out.write(bytes); }
        }); server.start();
    }
    @AfterEach void after() {
        if(torrents!=null) torrents.close(); server.stop(0);
        original.setUpgradeModeRevision(ConfigUtil.snapshot().getUpgradeModeRevision()); ConfigUtil.sync(original); DatabaseManager.close();
        if(previousConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previousConfig);
        ani.rss.util.basic.LogUtil.loadLogback();
    }
    private void configure(String type) throws Exception {
        kind=type; Path staging=Files.createDirectory(temporary.resolve("staging")),library=Files.createDirectory(temporary.resolve("library"));
        Config config=ConfigUtil.snapshot(); var settings=new UpgradeSettings(); settings.setStagingRoot(staging.toString()); settings.setReserveBytes(0);
        settings.setTvRoot(library.toString()); settings.setMovieRoot(library.toString()); settings.setDownloaderMappings(List.of(new UpgradeSettings.PathMapping("/staging",staging.toString())));
        config.setUpgrade(settings).setUpgradeMode(UpgradeMode.AUTOMATIC).setProxy(false).setDownloadToolType(type)
                .setDownloadToolHost("http://127.0.0.1:"+server.getAddress().getPort()).setDownloadToolUsername("test").setDownloadToolPassword("test-only");
        ConfigUtil.sync(config); config=ConfigUtil.snapshot();
        BaseDownload adapter=switch(type) { case "qBittorrent"->new qBittorrent(new DownloadService(),config); case "Transmission"->new Transmission(config); case "Aria2"->new Aria2(config); default->throw new IllegalArgumentException(type); };
        DownloaderClient client=new DownloaderClient(adapter,config); torrents=mockStatic(TorrentUtil.class); torrents.when(TorrentUtil::client).thenReturn(client);
        repository=new UpgradeRepository(); downloads=new UpgradeDownloadService(repository,new LibraryPathMappingService(),new MediaVerificationService(),new ReleaseAnalysisService(),new SpaceReservationService());
        job=new Job(); job.setId("operation"); job.setTargetId("target"); job.setIdentityKey("target:"+HASH); job.setState(JobState.DOWNLOADING); job.setGeneration(config.getUpgradeModeRevision());
        release=new Release(); release.setInfoHash(HASH); release.setTitle("two-episode-pack"); release.setTorrentUrl("magnet:?xt=urn:btih:"+HASH); release.setExpectedBytes(100);
    }
    private void save() { repository.save("upgrade_jobs",job.getId(),job.getTargetId(),job.getIdentityKey(),job.getState().name(),1,job); }
    private List<Path> payload() throws IOException {
        Path directory=Path.of(job.getStagingPath()); return List.of(Files.writeString(directory.resolve("one.mkv"),"first retained payload"),Files.writeString(directory.resolve("two.mkv"),"second retained payload"));
    }
    private void assertPayload(List<Path> files) throws IOException {
        assertEquals("first retained payload",Files.readString(files.get(0))); assertEquals("second retained payload",Files.readString(files.get(1)));
    }
    private void assertNoFileDeletionRequest() {
        if("qBittorrent".equals(kind)) assertTrue(lastStop.contains("deleteFiles=false"));
        else if("Transmission".equals(kind)) assertFalse(JsonParser.parseString(lastStop).getAsJsonObject().getAsJsonObject("params").get("delete_local_data").getAsBoolean());
        else assertEquals("aria2.removeDownloadResult",JsonParser.parseString(lastStop).getAsJsonObject().get("method").getAsString());
    }
    @ParameterizedTest @ValueSource(strings={"qBittorrent","Transmission","Aria2"})
    void submissionRestartFileListCompletionAndWholeTaskRemovalRetainBothPayloads(String type) throws Exception {
        configure(type); assertNull(downloads.submit(job,release)); save(); List<Path> files=payload();
        var transferring=downloads.task(job,release).orElseThrow(); assertFalse(transferring.finished());
        assertEquals(2,transferring.getFilesSupplier().get().size());
        DatabaseManager.reopen(); job=repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow();
        assertNotNull(downloads.submit(job,release)); assertEquals(1,submissions.get()); assertTrue(new UpgradeOwnershipService().owns(job));
        finished=true; var complete=downloads.task(job,release).orElseThrow(); assertTrue(complete.finished());
        var names=complete.getFilesSupplier().get(); assertTrue(names.stream().anyMatch(name->name.endsWith("one.mkv"))); assertTrue(names.stream().anyMatch(name->name.endsWith("two.mkv")));
        downloads.stopOwned(job); assertNull(job.getRemoteTaskId()); assertFalse(present); assertTrue(downloads.task(job,release).isEmpty());
        assertNoFileDeletionRequest(); assertPayload(files);
    }
    @ParameterizedTest @ValueSource(strings={"qBittorrent","Transmission","Aria2"})
    void aDisappearedRemoteTaskReconcilesAfterRestartAndCanSubmitAgainWithoutDeletingPayload(String type) throws Exception {
        configure(type); downloads.submit(job,release); save(); List<Path> files=payload(); present=false;
        DatabaseManager.reopen(); job=repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow();
        assertTrue(downloads.task(job,release).isEmpty()); downloads.stopOwned(job); assertNull(job.getRemoteTaskId()); assertEquals(0,stopAttempts.get());
        assertPayload(files); downloads.submit(job,release); assertEquals(2,submissions.get()); assertTrue(present); assertPayload(files);
    }
    @ParameterizedTest @ValueSource(strings={"qBittorrent","Transmission","Aria2"})
    void failedStopRetainsOwnershipAndPayloadUntilTheNoDeleteRetrySucceeds(String type) throws Exception {
        configure(type); downloads.submit(job,release); save(); List<Path> files=payload(); finished=true; rejectStop=true;
        assertEquals("OWNED_TRANSFER_STOP_FAILED",assertThrows(IOException.class,()->downloads.stopOwned(job)).getMessage());
        assertNotNull(job.getRemoteTaskId()); assertTrue(new UpgradeOwnershipService().owns(job)); assertTrue(present); assertPayload(files);
        rejectStop=false; downloads.stopOwned(job); assertNull(job.getRemoteTaskId()); assertFalse(present); assertNoFileDeletionRequest(); assertPayload(files);
    }
    @ParameterizedTest @ValueSource(strings={"qBittorrent","Transmission","Aria2"})
    void aReusedTaskIdWithAnotherKnownHashCannotBeAdoptedOrStopped(String type) throws Exception {
        configure(type); downloads.submit(job,release); List<Path> files=payload();
        if("Transmission".equals(type)) job.setRemoteTaskId("7"); // Exercise a persisted numeric reference from an older run.
        listedHash="f".repeat(40);
        assertTrue(downloads.task(job,release).isEmpty()); downloads.stopOwned(job);
        assertNull(job.getRemoteTaskId()); assertEquals(0,stopAttempts.get()); assertTrue(present); assertPayload(files);
    }

    private String respond(String path,String body) {
        if("qBittorrent".equals(kind)) {
            if(path.endsWith("/info")) return present?GsonStatic.toJson(List.of(Map.of("hash",listedHash,"name","two-episode-pack","completed",finished?100:40,"size",100,"state",finished?"stoppedUP":"downloading","save_path",remoteRoot,"tags","AUTOMATIC_UPGRADE"))):"[]";
            if(path.endsWith("/files")) return "[{\"name\":\"one.mkv\",\"priority\":1,\"size\":50},{\"name\":\"two.mkv\",\"priority\":1,\"size\":50}]";
            if(path.endsWith("/add")) { submissions.incrementAndGet(); present=true; remoteRoot=form(body).get("savepath"); }
            if(path.endsWith("/delete")) present=false;
            return "Ok.";
        }
        JsonObject request=JsonParser.parseString(body).getAsJsonObject(); String method=request.get("method").getAsString(); Object result=Map.of();
        if("Transmission".equals(kind)) {
            if(method.equals("torrent_add")) {
                boolean duplicate=present; submissions.incrementAndGet(); present=true; remoteRoot=request.getAsJsonObject("params").get("download_dir").getAsString();
                result=Map.of(duplicate?"torrent_duplicate":"torrent_added",Map.of("id",7,"hash_string",HASH));
            } else if(method.equals("torrent_get")) result=Map.of("torrents",present?List.of(transmissionTask()):List.of());
            else if(method.equals("torrent_remove")) present=false;
        } else {
            if(method.equals("aria2.addUri")) { submissions.incrementAndGet(); present=true; remoteRoot=request.getAsJsonArray("params").get(2).getAsJsonObject().get("dir").getAsString(); result="payload-gid"; }
            else if(method.equals("aria2.tellActive")) result=present&&!finished?List.of(ariaTask()):List.of();
            else if(method.equals("aria2.tellStopped")) result=present&&finished?List.of(ariaTask()):List.of();
            else if(method.equals("aria2.tellWaiting")) result=List.of();
            else if(method.startsWith("aria2.remove")) { present=false; result="OK"; }
        }
        return GsonStatic.toJson(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result));
    }
    private Map<String,Object> transmissionTask() {
        var task=new LinkedHashMap<String,Object>(); task.put("id",7); task.put("hash_string",listedHash); task.put("name","two-episode-pack");
        task.put("have_valid",finished?100:40); task.put("total_size",100); task.put("download_dir",remoteRoot); task.put("status",finished?6:4); task.put("is_finished",finished);
        task.put("labels",List.of("AUTOMATIC_UPGRADE")); task.put("files",List.of(Map.of("name","one.mkv"),Map.of("name","two.mkv"))); return task;
    }
    private Map<String,Object> ariaTask() {
        return Map.of("gid","payload-gid","infoHash",listedHash,"dir",remoteRoot,"status",finished?"complete":"active","completedLength",finished?100:40,"totalLength",100,
                "bittorrent",Map.of("info",Map.of("name","two-episode-pack")),"files",List.of(Map.of("path",remoteRoot+"/one.mkv"),Map.of("path",remoteRoot+"/two.mkv")));
    }
    private Map<String,String> form(String body) {
        Map<String,String> fields=new HashMap<>();
        for(String entry:body.split("&")) { String[] pair=entry.split("=",2); if(pair.length==2) fields.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8)); }
        return fields;
    }
}
