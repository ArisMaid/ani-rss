package ani.rss.upgrade;

import ani.rss.util.basic.HttpReq;
import cn.hutool.http.HttpRequest;
import com.google.gson.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import java.io.*;

public final class UpgradeHttp {
    private UpgradeHttp() {}
    private static final Semaphore GLOBAL=new Semaphore(4,true);
    private static final ConcurrentHashMap<String,Semaphore> SOURCES=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Long> NEXT=new ConcurrentHashMap<>();
    public static String resourceUrl(String url) {
        URI uri=URI.create(url);
        if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getFragment()!=null) throw new IllegalArgumentException("资源地址必须是无凭据的 HTTP(S) 地址");
        return url;
    }
    public static String base(String url) {
        URI uri=URI.create(url);
        if(!("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))
                ||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)
            throw new IllegalArgumentException("服务地址必须是无凭据和查询参数的 HTTP(S) 地址");
        return url.replaceAll("/+$","");
    }
    public static String query(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    public static JsonObject get(String url,Map<String,String> headers) { return execute(HttpReq.get(url),headers,null); }
    public static JsonObject post(String url,Map<String,String> headers,Object body) { return execute(HttpReq.post(url),headers,body); }
    public static JsonObject execute(HttpRequest request,Map<String,String> headers,Object body) {
        String text=executeText(request,headers,body);
        if(text.isEmpty()) return new JsonObject();
        try {
            JsonElement json=JsonParser.parseString(text);
            if(json.isJsonArray()) { JsonObject wrapper=new JsonObject(); wrapper.add("data",json); return wrapper; }
            if(!json.isJsonObject()) throw new SourceFailure("UPSTREAM_SCHEMA_CHANGED",false);
            return json.getAsJsonObject();
        } catch(SourceFailure e) { throw e; }
        catch(RuntimeException e) { throw new SourceFailure("UPSTREAM_UNAVAILABLE",true); }
    }
    public static String getText(String url) { return executeText(HttpReq.get(url).timeout(10000),Map.of(),null); }
    private static String executeText(HttpRequest request,Map<String,String> headers,Object body) {
        String host=URI.create(request.getUrl()).getHost();
        Semaphore source=SOURCES.computeIfAbsent(host,k->new Semaphore(2,true));
        boolean global=false,local=false;
        try {
        if(!GLOBAL.tryAcquire(5,TimeUnit.SECONDS)) throw new SourceFailure("HTTP_CONCURRENCY_LIMIT",true);
        global=true;
        if(!source.tryAcquire(5,TimeUnit.SECONDS)) throw new SourceFailure("HTTP_SOURCE_CONCURRENCY_LIMIT",true);
        local=true;
        long wait;
        synchronized(NEXT) { long now=System.currentTimeMillis(); long at=Math.max(now,NEXT.getOrDefault(host,0L)); NEXT.put(host,at+1000); wait=at-now; }
        if(wait>0) Thread.sleep(Math.min(wait,5000));
        headers.forEach(request::header);
        request.setFollowRedirects(false);
        if(body!=null) request.body(ani.rss.commons.GsonStatic.toJson(body));
        try(var response=request.executeAsync()) {
            if(response.getStatus()<200||response.getStatus()>=300)
                throw new SourceFailure("UPSTREAM_HTTP_"+response.getStatus(),response.getStatus()==429||response.getStatus()>=500,retryAfter(response.header("Retry-After")));
            byte[] bytes=response.bodyStream().readNBytes(16*1024*1024+1);
            if(bytes.length>16*1024*1024) throw new SourceFailure("UPSTREAM_RESPONSE_TOO_LARGE",false);
            return new String(bytes,StandardCharsets.UTF_8);
        } catch(SourceFailure e) { throw e; }
        catch(RuntimeException|IOException e) { throw new SourceFailure("UPSTREAM_UNAVAILABLE",true); }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new SourceFailure("HTTP_INTERRUPTED",true); }
        finally { if(local) source.release(); if(global) GLOBAL.release(); }
    }
    private static long retryAfter(String value) {
        if(value==null) return 0;
        try { return Math.min(86400000,Math.max(0,Long.parseLong(value)*1000)); }
        catch(NumberFormatException e) { try { return Math.min(86400000,Math.max(0,java.time.ZonedDateTime.parse(value,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis())); } catch(RuntimeException ignored) { return 0; } }
    }
    public static String string(JsonObject o,String key) { return o!=null&&o.has(key)&&o.get(key).isJsonPrimitive()?o.get(key).getAsString():null; }
    public static int integer(JsonObject o,String key,int fallback) { return o!=null&&o.has(key)&&!o.get(key).isJsonNull()?o.get(key).getAsInt():fallback; }
    public static JsonArray array(JsonObject o,String key) { return o!=null&&o.has(key)&&o.get(key).isJsonArray()?o.getAsJsonArray(key):new JsonArray(); }
    public static class SourceFailure extends RuntimeException {
        private final boolean retryable;
        private final long retryAfterMillis;
        public SourceFailure(String code,boolean retryable) { this(code,retryable,0); }
        public SourceFailure(String code,boolean retryable,long retryAfterMillis) { super(code); this.retryable=retryable; this.retryAfterMillis=retryAfterMillis; }
        public boolean retryable() { return retryable; }
        public long retryAfterMillis() { return retryAfterMillis; }
    }
}
