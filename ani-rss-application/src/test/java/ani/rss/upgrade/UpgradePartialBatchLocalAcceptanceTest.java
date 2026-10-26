package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.download.qBittorrent;
import ani.rss.entity.*;
import ani.rss.service.DownloadService;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real qBittorrent metadata/file-priority check; payload here is synthetic, not a media-download claim. */
@EnabledIfSystemProperty(named="upgrade.local.acceptance",matches=".+")
class UpgradePartialBatchLocalAcceptanceTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void selectsOnlyRequiredFilesAndNeverResumesExistingPausedTasks(boolean standalone) throws Exception {
        Path batch=Path.of(System.getProperty("upgrade.local.acceptance")).toAbsolutePath().normalize();
        Path project=Path.of("").toAbsolutePath().normalize().getParent(); assertTrue(batch.startsWith(project.resolve("output")));
        Path run=Files.createDirectory(batch.resolve("partial-files-"+System.currentTimeMillis()));
        Path credentials=Path.of(System.getProperty("upgrade.acceptance.qb.credentials",project.resolve("output/subscription-usability/qb-credentials.json").toString()));
        JsonObject qb=JsonParser.parseString(Files.readString(credentials)).getAsJsonObject();
        Config config=ConfigUtil.snapshot(); config.setDownloadToolHost(qb.get("host").getAsString()).setDownloadToolUsername(qb.get("username").getAsString())
                .setDownloadToolPassword(qb.get("password").getAsString()).setRename(true).setUpLimit(0L).setDlLimit(0L).setPriorityKeywordsEnable(false);
        Ani subscription=AniUtil.createAni().setId(UUID.randomUUID().toString()).setTitle("Partial batch").setSeason(1).setOffset(0).setBgmUrl("https://bgm.tv/subject/100").setQualityPolicyId("test");
        subscription.setOva(standalone);
        DownloadService lookup=mock(DownloadService.class); when(lookup.findAniByDownloadPath(any())).thenReturn(Optional.of(subscription));
        var adapter=new qBittorrent(lookup,config); assertTrue(adapter.login(false,config));
        var before=adapter.inventory().value();
        List<Map<String,Object>> files=new ArrayList<>(); ByteArrayOutputStream payload=new ByteArrayOutputStream();
        for(int episode=1;episode<=3;episode++) {
            byte[] bytes=("chapter-"+episode).getBytes(StandardCharsets.UTF_8); payload.write(bytes);
            files.add(Map.of("length",bytes.length,"path",List.of("Example - 0"+episode+".mkv")));
        }
        var info=new TreeMap<String,Object>(); info.put("name","partial-"+UUID.randomUUID()); info.put("files",files); info.put("piece length",16384); info.put("private",1);
        info.put("pieces",MessageDigest.getInstance("SHA-1").digest(payload.toByteArray()));
        Path torrent=Files.write(run.resolve("input.torrent"),bencode(Map.of("info",info)));
        String hash=TorrentUtil.getCanonicalInfoHash(torrent.toFile()); assertFalse(hash.isBlank());
        Item item=new Item().setTitle("[LoliHouse] Example [01-03][1080p][CHS]").setReName("Partial batch S01E01-03").setEpisode(1D)
                .setSubgroup("LoliHouse").setMaster(true).setInfoHash(hash).setSelectedEpisodes(Set.of("1","3"));
        if(standalone) item.setReName("Partial OVA batch");
        try {
            assertTrue(adapter.downloadResult(subscription,item,run.toString(),torrent.toFile()).isSuccess());
            Optional<ani.rss.entity.torrent.TorrentsInfo> observedTask=Optional.empty();
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(observedTask.isEmpty()&&System.nanoTime()<deadline) {
                observedTask=adapter.inventory().value().stream().filter(t->hash.equals(t.getHash())).findFirst();
                if(observedTask.isEmpty()) Thread.sleep(100);
            }
            var task=observedTask.orElseThrow();
            assertTrue(adapter.rename(task));
            var observed=adapter.files(task,false); assertEquals(3,observed.size());
            assertEquals(0,observed.stream().filter(f->f.getName().contains("02.mkv")).findFirst().orElseThrow().getPriority());
            assertTrue(observed.stream().filter(f->!f.getName().contains("02.mkv")).allMatch(f->f.getPriority()>0));
            var after=adapter.inventory().value();
            for(var original:before) if(original.getState()!=null&&original.getState().name().startsWith("stopped"))
                assertTrue(after.stream().filter(t->original.getHash().equals(t.getHash())).allMatch(t->t.getState().name().startsWith("stopped")),"Existing paused task must remain paused");
            Files.writeString(run.resolve("result.json"),GsonStatic.toJson(Map.of("passed",true,"kind","real-qb-file-priority-with-synthetic-payload","hash",hash,"files",observed,"existingPausedTasksPreserved",true)));
        } finally {
            for(var task:adapter.inventory().value()) if(hash.equals(task.getHash())) assertTrue(adapter.delete(task,false));
        }
    }
    private static byte[] bencode(Object value) throws Exception {
        var out=new ByteArrayOutputStream();
        if(value instanceof Map<?,?> map) { out.write('d'); for(var key:new TreeSet<>(map.keySet().stream().map(Object::toString).toList())) { out.write(bencode(key));out.write(bencode(map.get(key))); }out.write('e'); }
        else if(value instanceof List<?> list) { out.write('l'); for(var item:list)out.write(bencode(item));out.write('e'); }
        else if(value instanceof Number number) out.write(("i"+number+"e").getBytes(StandardCharsets.US_ASCII));
        else { byte[] bytes=value instanceof byte[] b?b:value.toString().getBytes(StandardCharsets.UTF_8);out.write((bytes.length+":").getBytes(StandardCharsets.US_ASCII));out.write(bytes); }
        return out.toByteArray();
    }
}
