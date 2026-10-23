package ani.rss.upgrade;

import ani.rss.commons.GsonStatic;
import ani.rss.upgrade.UpgradeModels.Release;
import com.google.gson.*;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

@Service
public class MediaVerificationService {
    private static final ExecutorService READERS=Executors.newCachedThreadPool(r->{ Thread t=new Thread(r,"upgrade-process-output"); t.setDaemon(true); return t; });
    public record Verified(Release attributes,long size,long modifiedAt,String fileKey,String sha256) {
        public Verified { attributes=UpgradeModels.snapshot(attributes,Release.class); }
        @Override public Release attributes() { return UpgradeModels.snapshot(attributes,Release.class); }
    }
    public boolean available(String executable) {
        try { run(List.of(executable,"-version"),10,100000); return true; } catch(Exception e) { return false; }
    }
    public Verified verify(Path path,Release declared,UpgradeSettings s) throws IOException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(120);
        BasicFileAttributes before=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!before.isRegularFile()||before.isSymbolicLink()||before.size()==0) throw new IOException("MEDIA_NOT_REGULAR_FILE");
        String json=run(List.of(s.getFfprobe(),"-v","error","-show_streams","-show_format","-of","json",path.toString()),remaining(deadline),1_048_576);
        JsonObject probe=JsonParser.parseString(json).getAsJsonObject();
        Release actual=GsonStatic.fromJson(GsonStatic.toJson(declared),Release.class);
        boolean video=false;
        for(JsonElement element:UpgradeHttp.array(probe,"streams")) {
            JsonObject stream=element.getAsJsonObject(); String type=UpgradeHttp.string(stream,"codec_type");
            if("video".equals(type)&&!video) {
                actual.setResolution(UpgradeHttp.integer(stream,"height",0)); video=true;
                String codec=UpgradeHttp.string(stream,"codec_name");
                actual.setCodec("h264".equals(codec)?"AVC":"hevc".equals(codec)?"HEVC":"av1".equals(codec)?"AV1":codec);
            }
        }
        embeddedSubtitles(path,probe,actual,s,deadline);
        if(!video||!probe.has("format")) throw new IOException("MEDIA_VIDEO_STREAM_MISSING");
        JsonObject format=probe.getAsJsonObject("format");
        String container=Objects.toString(UpgradeHttp.string(format,"format_name"),"");
        String extension=UpgradePaths.fileName(path).toLowerCase(Locale.ROOT);
        actual.setContainer(container.contains("webm")&&extension.endsWith(".webm")?"webm":container.contains("matroska")?"mkv":container.contains("mp4")?"mp4":container.contains("mpegts")?"ts":container.contains("avi")?"avi":null);
        if(actual.getContainer()==null) throw new IOException("MEDIA_CONTAINER_UNSUPPORTED");
        double duration;
        try { duration=Double.parseDouble(UpgradeHttp.string(format,"duration")); } catch(Exception e) { throw new IOException("MEDIA_DURATION_UNKNOWN"); }
        if(!Double.isFinite(duration)||duration<=0) throw new IOException("MEDIA_INVALID_DURATION");
        for(double time:new double[]{0,Math.max(0,duration/2-1),Math.max(0,duration-3)}) {
            run(List.of(s.getFfmpeg(),"-v","error","-xerror","-nostdin","-ss",Double.toString(time),"-i",path.toString(),"-t","2","-map","0:v:0","-f","null","-"),remaining(deadline),1_048_576);
        }
        String sha=hashWithinDeadline(path,deadline);
        BasicFileAttributes after=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(before.size()!=after.size()||!before.lastModifiedTime().equals(after.lastModifiedTime())||!Objects.equals(before.fileKey(),after.fileKey()))
            throw new IOException("MEDIA_CHANGED_DURING_VERIFICATION");
        remaining(deadline);
        return new Verified(actual,after.size(),after.lastModifiedTime().toMillis(),Objects.toString(after.fileKey(),null),sha);
    }
    private static void embeddedSubtitles(Path path,JsonObject probe,Release actual,UpgradeSettings settings,long deadline) throws IOException {
        Release observed=new Release(); boolean complete=true; int streams=0;
        for(JsonElement element:UpgradeHttp.array(probe,"streams")) {
            JsonObject stream=element.getAsJsonObject();
            if(!"subtitle".equals(UpgradeHttp.string(stream,"codec_type"))) continue;
            streams++;
            Release track=new Release(); JsonObject tags=stream.has("tags")?stream.getAsJsonObject("tags"):new JsonObject();
            JsonObject normalized=new JsonObject(); normalized.addProperty("Language",UpgradeHttp.string(tags,"language")); normalized.addProperty("Title",UpgradeHttp.string(tags,"title"));
            EmbyLibraryIndexService.mergeSubtitle(track,normalized);
            int index=UpgradeHttp.integer(stream,"index",-1); boolean textKnown=false;
            // Text subtitles can be converted without decoding the video. Bitmap
            // subtitles remain usable declaration evidence; no OCR is required.
            if(index>=0&&streams<=8) try {
                String text=run(List.of(settings.getFfmpeg(),"-v","error","-nostdin","-i",path.toString(),"-map","0:"+index,"-c:s","srt","-f","srt","pipe:1"),Math.min(8,remaining(deadline)),1_048_576);
                Release parsed=new Release(); supplementText(parsed,text,"EMBEDDED_SUBTITLE_TEXT");
                if(!parsed.getLanguages().isEmpty()) {
                    // A complete bounded Chinese/Japanese text stream outweighs a
                    // contradictory language tag or copied track title.
                    track.setLanguages(new TreeSet<>(parsed.getLanguages())); textKnown=true;
                    var evidence=new UpgradeModels.Evidence("EMBEDDED_SUBTITLE_TEXT","languages",String.join("+",parsed.getLanguages()));
                    evidence.setSourceReference("stream:"+index); evidence.setEvidenceSnippet(text.substring(0,Math.min(600,text.length()))); evidence.setVerificationLevel("OBSERVED"); actual.getEvidence().add(evidence);
                }
            } catch(IOException error) { if(Thread.currentThread().isInterrupted()) throw error; }
            var trackEvidence=new UpgradeModels.Evidence("EMBEDDED_SUBTITLE_TRACK","languages",String.join("+",track.getLanguages()));
            trackEvidence.setSourceReference("stream:"+index); trackEvidence.setEvidenceSnippet(normalized.toString()); trackEvidence.setVerificationLevel("OBSERVED"); actual.getEvidence().add(trackEvidence);
            complete&=textKnown; observed.getLanguages().addAll(track.getLanguages());
        }
        Set<String> declared=new TreeSet<>(actual.getLanguages());
        if("SOFT".equals(actual.getSubtitlePackaging())&&complete) {
            actual.getLanguages().retainAll(observed.getLanguages());
            if(!declared.equals(actual.getLanguages())) {
                var evidence=new UpgradeModels.Evidence("MEDIA_VERIFICATION","subtitleDeclarationConflict","声明="+declared+"；实际可读内封="+observed.getLanguages());
                evidence.setVerificationLevel("OBSERVED"); actual.getEvidence().add(evidence);
            }
        }
        actual.getLanguages().addAll(observed.getLanguages());
        if(actual.getLanguages().contains("CHS")||actual.getLanguages().contains("CHT")) actual.getLanguages().remove("ZHO");
        if(streams>0&&actual.getSubtitlePackaging()==null) actual.setSubtitlePackaging("SOFT");
    }
    public void supplementSubtitles(Release attributes,Path video,List<Path> subtitles) throws IOException {
        String base=UpgradePaths.fileName(video).replaceFirst("\\.[^.]+$","");
        Release observed=new Release(); boolean complete=true; int matched=0;
        // Retain independently observed embedded capability when an external
        // track disagrees with the publisher's declaration.
        for(var evidence:attributes.getEvidence()) if("EMBEDDED_SUBTITLE_TRACK".equals(evidence.getSource())&&"OBSERVED".equals(evidence.getVerificationLevel()))
            for(String language:evidence.getQuote().split("\\+")) if(Set.of("CHS","CHT","JPN","ZHO").contains(language)) observed.getLanguages().add(language);
        for(Path subtitle:subtitles) {
            if(!UpgradePaths.fileName(subtitle).startsWith(base+".")) continue;
            matched++;
            var a=Files.readAttributes(subtitle,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if(!a.isRegularFile()||a.isSymbolicLink()||a.size()>16*1024*1024) throw new IOException("SUBTITLE_FILE_UNSUPPORTED");
            byte[] sample; try(var in=Files.newInputStream(subtitle)) { sample=in.readNBytes(1_048_576); }
            String text=sample.length>=2&&(sample[0]==(byte)0xff&&sample[1]==(byte)0xfe||sample[0]==(byte)0xfe&&sample[1]==(byte)0xff)
                    ?new String(sample,StandardCharsets.UTF_16):new String(sample,StandardCharsets.UTF_8);
            Release parsed=new Release(); supplementText(parsed,text,"EXTERNAL_SUBTITLE");
            boolean textKnown=a.size()<=sample.length&&text.indexOf('\ufffd')<0&&!parsed.getLanguages().isEmpty(); complete&=textKnown;
            if(textKnown) {
                observed.getLanguages().addAll(parsed.getLanguages());
                var evidence=new UpgradeModels.Evidence("EXTERNAL_SUBTITLE_TEXT","languages",String.join("+",parsed.getLanguages()));
                evidence.setEvidenceSnippet(text.substring(0,Math.min(600,text.length()))); evidence.setVerificationLevel("OBSERVED"); attributes.getEvidence().add(evidence);
            } else {
                observed.getLanguages().addAll(parsed.getLanguages());
                Release name=new ReleaseAnalysisService().parse(UpgradePaths.fileName(subtitle),attributes.getSubjectId(),"SUBTITLE_FILENAME"); observed.getLanguages().addAll(name.getLanguages());
            }
            if(attributes.getSubtitlePackaging()==null) attributes.setSubtitlePackaging("EXTERNAL");
        }
        if("EXTERNAL".equals(attributes.getSubtitlePackaging())&&complete) {
            Set<String> permitted=new TreeSet<>(observed.getLanguages());
            if(permitted.contains("ZHO")) permitted.addAll(List.of("CHS","CHT"));
            Set<String> declared=new TreeSet<>(attributes.getLanguages()); attributes.getLanguages().retainAll(permitted);
            if(!declared.equals(attributes.getLanguages())) attributes.getEvidence().add(new UpgradeModels.Evidence("MEDIA_VERIFICATION","subtitleDeclarationConflict","声明="+declared+"；实际字幕="+observed.getLanguages()+"；关联外挂数="+matched));
        }
        attributes.getLanguages().addAll(observed.getLanguages());
        if(attributes.getLanguages().contains("CHS")||attributes.getLanguages().contains("CHT")) attributes.getLanguages().remove("ZHO");
    }
    public static void supplementText(Release release,String text,String source) {
        // Shared Japanese kanji cannot establish a Chinese subtitle track.
        String simplified="这们说发无开门风过进与书语爱见头车东";
        String traditional="這們說裡還讓點麼";
        long chs=text.codePoints().filter(c->simplified.indexOf(c)>=0).distinct().count();
        long cht=text.codePoints().filter(c->traditional.indexOf(c)>=0).distinct().count();
        if(chs>=3) release.getLanguages().add("CHS"); if(cht>=3) release.getLanguages().add("CHT");
        if(text.codePoints().filter(c->c>=0x3041&&c<=0x30ff).count()>=6) release.getLanguages().add("JPN");
        if(chs>=3||cht>=3) release.getEvidence().add(new UpgradeModels.Evidence(source,"languages","字幕文本包含可辨识汉字脚本"));
    }
    private static int remaining(long deadline) throws IOException {
        long seconds=TimeUnit.NANOSECONDS.toSeconds(deadline-System.nanoTime());
        if(seconds<1) throw new IOException("MEDIA_VERIFICATION_TIMEOUT"); return (int)seconds;
    }
    private static String hashWithinDeadline(Path path,long deadline) throws IOException {
        Future<String> hash=READERS.submit(()->sha256(path));
        try { return hash.get(remaining(deadline),TimeUnit.SECONDS); }
        catch(TimeoutException error) { throw new IOException("MEDIA_VERIFICATION_TIMEOUT",error); }
        catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("MEDIA_PROCESS_INTERRUPTED",error); }
        catch(ExecutionException error) { if(error.getCause() instanceof IOException io) throw io; throw new IOException("MEDIA_HASH_FAILED",error); }
        finally { hash.cancel(true); }
    }
    public static String sha256(Path path) throws IOException {
        try(InputStream in=Files.newInputStream(path)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256"); byte[] buffer=new byte[1024*1024]; int count;
            while((count=in.read(buffer))>=0) { if(Thread.currentThread().isInterrupted()) throw new IOException("INTERRUPTED"); digest.update(buffer,0,count); }
            return HexFormat.of().formatHex(digest.digest());
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static String run(List<String> args,int seconds,int maxBytes) throws IOException {
        Process process=new ProcessBuilder(args).redirectErrorStream(true).start();
        Future<byte[]> output=READERS.submit(()->{
            try(InputStream in=process.getInputStream()) {
                byte[] bytes=in.readNBytes(maxBytes+1);
                if(bytes.length>maxBytes) throw new IOException("MEDIA_PROCESS_OUTPUT_LIMIT"); return bytes;
            }
        });
        try {
            if(!process.waitFor(seconds,TimeUnit.SECONDS)) throw new IOException("MEDIA_PROCESS_TIMEOUT");
            byte[] bytes=output.get(2,TimeUnit.SECONDS);
            if(process.exitValue()!=0) {
                String diagnostic=new String(bytes,StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                if(diagnostic.contains("decoder")&&(diagnostic.contains("not found")||diagnostic.contains("unsupported")||diagnostic.contains("not implemented"))) throw new IOException("MEDIA_DECODER_UNSUPPORTED");
                if(diagnostic.contains("invalid data")||diagnostic.contains("corrupt")||diagnostic.contains("error while decoding")) throw new IOException("MEDIA_CORRUPT");
                throw new IOException("MEDIA_PROCESS_FAILED_"+process.exitValue());
            }
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("MEDIA_PROCESS_INTERRUPTED",e); }
        catch(ExecutionException|TimeoutException e) { throw new IOException("MEDIA_PROCESS_OUTPUT_FAILED",e); }
        finally { if(process.isAlive()) process.destroyForcibly(); output.cancel(true); }
    }
}
