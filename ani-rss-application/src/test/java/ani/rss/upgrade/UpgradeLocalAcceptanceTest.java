package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: real local qBittorrent and media, isolated files, HTTP Emby fixture. */
@EnabledIfSystemProperty(named="upgrade.local.acceptance",matches=".+")
class UpgradeLocalAcceptanceTest {
    @Test void realDownloaderVerificationInstallationAndRestartPreserveOrdinaryInput() throws Exception {
        Path batch=Path.of(System.getProperty("upgrade.local.acceptance")).toAbsolutePath().normalize();
        Path project=Path.of("").toAbsolutePath().normalize().getParent();
        assertTrue(batch.startsWith(project.resolve("output")));
        Path run=Files.createDirectory(batch.resolve("media-run-"+System.currentTimeMillis()));
        Path library=Files.createDirectory(run.resolve("library")),staging=Files.createDirectory(run.resolve("staging"));
        Path credentials=Path.of(System.getProperty("upgrade.acceptance.qb.credentials",project.resolve("output/subscription-usability/qb-credentials.json").toString()));
        JsonObject qb=JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
        Path source;
        source=Path.of(System.getProperty("upgrade.acceptance.source")).toAbsolutePath().normalize();
        assertTrue(source.startsWith(project.resolve("subscription-test-downloads"))); assertTrue(Files.isRegularFile(source));
        String originalSha=MediaVerificationService.sha256(source);
        String previous=System.getProperty("CONFIG"); Config original=ConfigUtil.snapshot(); var originalClient=TorrentUtil.client();
        System.setProperty("CONFIG",run.resolve("config").toString()); DatabaseManager.close();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        List<String> httpCalls=Collections.synchronizedList(new ArrayList<>());
        long[] transferred={0};
        byte[][] torrent={null}; JsonObject[] embyPage={null},embyItem={null};
        server.createContext("/",x->{
            String path=x.getRequestURI().getPath(); httpCalls.add(x.getRequestMethod()+" "+x.getRequestURI());
            try {
                if(path.equals("/payload.mkv")) {
                    long start=0,end=Files.size(source)-1; String range=x.getRequestHeaders().getFirst("Range");
                    if(range!=null) { String[] parts=range.replace("bytes=","").split("-",-1); start=Long.parseLong(parts[0]); if(!parts[1].isBlank()) end=Long.parseLong(parts[1]); x.getResponseHeaders().set("Content-Range","bytes "+start+"-"+end+"/"+Files.size(source)); }
                    x.getResponseHeaders().set("Accept-Ranges","bytes"); x.sendResponseHeaders(range==null?200:206,end-start+1);
                    try(var file=new RandomAccessFile(source.toFile(),"r");var out=x.getResponseBody()) { file.seek(start); byte[] bytes=new byte[262144]; long left=end-start+1; while(left>0) { int n=file.read(bytes,0,(int)Math.min(bytes.length,left)); if(n<0) break; out.write(bytes,0,n); left-=n; synchronized(transferred) { transferred[0]+=n; } } }
                    return;
                }
                byte[] body=path.equals("/input.torrent")?torrent[0]:GsonStatic.toJson(path.startsWith("/Items/")?embyItem[0]:embyPage[0]).getBytes(StandardCharsets.UTF_8);
                x.sendResponseHeaders(200,body.length); try(var out=x.getResponseBody()) { out.write(body); }
            } finally { x.close(); }
        }); server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            String filename="[LoliHouse] Local acceptance - 10 [1080p][CHS][MKV].mkv";
            ByteArrayOutputStream pieces=new ByteArrayOutputStream();
            try(var input=Files.newInputStream(source)) { byte[] block; while((block=input.readNBytes(4*1024*1024)).length>0) pieces.write(MessageDigest.getInstance("SHA-1").digest(block)); }
            var info=new TreeMap<String,Object>(); info.put("length",Files.size(source)); info.put("name",filename); info.put("piece length",4*1024*1024); info.put("pieces",pieces.toByteArray()); info.put("private",1);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bencode(info)));
            torrent[0]=bencode(new TreeMap<>(Map.of("info",info,"url-list",List.of(base+"/payload.mkv"))));
            Files.write(run.resolve("input.torrent"),torrent[0]);
            var settings=new UpgradeSettings(); settings.setReserveBytes(0); settings.setLibraryIds(List.of("isolated"));
            settings.setTvRoot(library.toString()); settings.setMovieRoot(library.toString()); settings.setStagingRoot(staging.toString());
            settings.setDownloaderMappings(List.of(new UpgradeSettings.PathMapping(staging.toString(),staging.toString())));
            settings.setEmbyMappings(List.of(new UpgradeSettings.PathMapping("/media",library.toString())));
            settings.setEmbyUrl(base); settings.setEmbyApiKey("local-fixture-only"); settings.setDefaultPolicyId("policy");
            Config config=ConfigUtil.snapshot(); config.setUpgrade(settings).setUpgradeMode(UpgradeMode.AUTOMATIC).setProxy(false)
                    .setDownloadToolType("qBittorrent").setDownloadToolHost(qb.get("host").getAsString()).setDownloadToolUsername(qb.get("username").getAsString()).setDownloadToolPassword(qb.get("password").getAsString()).setDownloadCount(0);
            ConfigUtil.sync(config); TorrentUtil.loadDownloadTool(); assertTrue(TorrentUtil.client().connect(false).isSuccess());
            var before=TorrentUtil.client().inventory().value();
            Path old=library.resolve("[Fallback] Local acceptance - 10 [720p][CHS].mkv");
            Path subtitle=Files.writeString(run.resolve("baseline.srt"),"1\n00:00:00,000 --> 00:00:06,000\n这是我们说过的话，书和车都已经进来了。\n");
            MediaVerificationService.run(List.of("ffmpeg","-v","error","-nostdin","-f","lavfi","-i","color=c=black:s=1280x720:r=5","-i",subtitle.toString(),"-t","6","-map","0:v","-map","1:s","-c:v","libx264","-threads","1","-c:s","srt","-metadata:s:s:0","language=chi","-metadata:s:s:0","title=简体中文",old.toString()),30,100000);
            Path unrelated=Files.writeString(library.resolve("episode-11.keep"),"unrelated episode must remain"); String unrelatedSha=MediaVerificationService.sha256(unrelated);
            var repository=new UpgradeRepository(); var analysis=new ReleaseAnalysisService(); var paths=new LibraryPathMappingService(); var space=new SpaceReservationService();
            var policy=QualityPolicyPresets.catalog().presets().get(0).policy(); policy.setId("policy"); repository.save("upgrade_policies","policy",null,null,"ACTIVE",1,policy);
            Target target=new Target(); target.setId("target"); target.setSubjectId("511177"); target.setTitle("Local acceptance"); target.setType("TV"); target.setSeason(1); target.setLibraryId("isolated"); target.setPremiereDate("2026-07-01"); target.setOutputRoot(library.toString());
            repository.save("upgrade_targets","target","isolated","511177:isolated","MONITORING",1,target);
            Campaign campaign=new Campaign(); campaign.setId("campaign"); campaign.setLibraryId("isolated"); campaign.setPolicyId("policy"); campaign.setStartDate("2026-07-01"); campaign.setEndDate("2026-09-30"); repository.save("upgrade_campaigns","campaign",null,null,"ENABLED",1,campaign);
            embyItem[0]=JsonParser.parseString("{\"Id\":\"episode10\",\"Type\":\"Episode\",\"ProviderIds\":{\"Bangumi\":\"511177\"},\"IndexNumber\":10,\"ParentIndexNumber\":1,\"MediaSources\":[{\"Id\":\"source10\",\"Path\":\"/media/"+old.getFileName()+"\",\"Container\":\"mkv\",\"MediaStreams\":[{\"Type\":\"Video\",\"Height\":720},{\"Type\":\"Subtitle\",\"Language\":\"chi\",\"Title\":\"简体中文\"}]}]}").getAsJsonObject();
            embyPage[0]=new JsonObject(); embyPage[0].addProperty("TotalRecordCount",1); JsonArray items=new JsonArray(); items.add(embyItem[0]); embyPage[0].add("Items",items);
            var emby=new EmbyLibraryIndexService(repository,paths,analysis); assertTrue(emby.scanStep("isolated")); assertTrue(emby.fresh("isolated")); assertEquals(1,emby.adopt(target).size());
            var provider=new ReleaseProvider(repository,analysis); Release candidate=analysis.parse(filename,"511177","LOCAL_ACCEPTANCE"); candidate.setTargetId("target"); candidate.setSourceId(hash); candidate.setInfoHash(hash); candidate.setTorrentUrl(base+"/input.torrent"); candidate.setExpectedBytes(Files.size(source)); candidate.setSeason(1); provider.accept(candidate);
            int alternative=0;
            for(String title:List.of("[桜都字幕组] Local acceptance - 10 [2160p][CHS][MKV]","[桜都字幕组] Local acceptance - 10 [720p][CHS][MKV]","[绿茶字幕组] Local acceptance - 10 [1080p][CHT][MKV]","[Fallback] Local acceptance - 10 [1080p][CHS][MKV]")) {
                Release other=analysis.parse(title,"511177","LOCAL_ACCEPTANCE"); other.setTargetId("target"); other.setSourceId("alternative-"+alternative); other.setInfoHash(String.format("%040x",++alternative)); other.setSeason(1); provider.accept(other);
            }
            var commits=new UpgradeCommitService(repository,paths,space); var downloads=new UpgradeDownloadService(repository,paths,new MediaVerificationService(),analysis,space);
            var scheduler=new UpgradeScheduler(repository,new UpgradeCampaignService(repository,new QualityPolicyEngine()),null,emby,provider,null,new QualityPolicyEngine(),downloads,commits,space,null,null,null,new UpgradeNotificationService(repository));
            scheduler.reanalyzeTarget(target); List<Job> jobs=repository.all("upgrade_jobs","target",null,Job.class); assertEquals(1,jobs.size()); Job job=jobs.get(0); assertEquals(JobState.QUEUED,job.getState());
            scheduler.process(job); assertEquals(JobState.DOWNLOADING,job.getState()); assertTrue(new UpgradeOwnershipService().owns(job));
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(6);
            while(System.nanoTime()<deadline) { var task=downloads.task(job,candidate); if(task.isPresent()&&task.get().finished()) break; Thread.sleep(1000); }
            assertTrue(downloads.task(job,candidate).orElseThrow().finished(),"Actual local qBittorrent transfer must finish"); assertTrue(transferred[0]>=Files.size(source));
            scheduler.process(job); assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(old)); assertEquals(unrelatedSha,MediaVerificationService.sha256(unrelated));
            List<Version> active=repository.all("upgrade_versions","target","ACTIVE",Version.class); assertEquals(1,active.size()); Version installed=active.get(0); assertEquals(originalSha,MediaVerificationService.sha256(Path.of(installed.getPath()))); assertTrue(installed.getAttributes().getLanguages().contains("CHS")); assertEquals(1080,installed.getAttributes().getResolution());
            long installs=repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).count(); assertEquals(1,installs);
            DatabaseManager.close(); scheduler.reanalyzeTarget(target); assertEquals(1,repository.all("upgrade_jobs","target",null,Job.class).stream().filter(j->"UPGRADE".equals(j.getKind())).count());
            assertEquals(originalSha,MediaVerificationService.sha256(source));
            var afterTasks=TorrentUtil.client().inventory().value();
            assertTrue(before.stream().allMatch(t->afterTasks.stream().anyMatch(a->Objects.equals(t.getHash(),a.getHash()))));
            downloads.stopOwned(job); commits.saveJob(job); assertTrue(Files.exists(source));
            Files.writeString(run.resolve("result.json"),GsonStatic.toJson(Map.of("passed",true,"kind","real-qb-media-with-http-emby-fixture","sourceSha256",originalSha,"transferredBytes",transferred[0],"jobId",job.getId(),"infoHash",hash,"installed",installed,"ordinarySourcePreserved",true,"restartDidNotDuplicate",true,"httpCalls",httpCalls)));
            System.out.println("LOCAL_ACCEPTANCE_PASSED "+run);
        } finally {
            server.stop(0); executor.shutdownNow(); DatabaseManager.close(); cn.hutool.core.bean.BeanUtil.copyProperties(original,ConfigUtil.CONFIG);
            org.springframework.test.util.ReflectionTestUtils.setField(TorrentUtil.class,"CLIENT",originalClient);
            if(previous==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previous);
        }
    }
    private static byte[] bencode(Object value) throws IOException {
        var out=new ByteArrayOutputStream();
        if(value instanceof Map<?,?> map) { out.write('d'); for(var e:map.entrySet()) { out.write(bencode(e.getKey().toString())); out.write(bencode(e.getValue())); } out.write('e'); }
        else if(value instanceof List<?> list) { out.write('l'); for(Object item:list) out.write(bencode(item)); out.write('e'); }
        else if(value instanceof Number number) out.write(("i"+number+"e").getBytes(StandardCharsets.US_ASCII));
        else { byte[] bytes=value instanceof byte[] b?b:value.toString().getBytes(StandardCharsets.UTF_8); out.write((bytes.length+":").getBytes(StandardCharsets.US_ASCII)); out.write(bytes); }
        return out.toByteArray();
    }
}
