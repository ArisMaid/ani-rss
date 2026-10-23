package ani.rss.upgrade;

import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.Release;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpgradeAiClientTest {
    @TempDir Path directory;
    private String prior; private Config original; private UpgradeSettings settings;
    private UpgradeRepository repository; private DeepSeekAnalysisClient client;
    @BeforeEach void before() {
        prior=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        settings=new UpgradeSettings(); settings.setAiEnabled(true); settings.setAiApiKey("fixture-only-key"); settings.setAiModel("fixture-model"); settings.setAiTestFingerprint(DeepSeekAnalysisClient.fingerprint(settings)); ConfigUtil.CONFIG.setUpgrade(settings);
        repository=new UpgradeRepository(); client=new DeepSeekAnalysisClient(repository);
    }
    @AfterEach void after() { DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }
    private static Release release() { Release r=new Release(); r.setId("r"); r.setSubjectId("100"); r.setTitle("[A] Example - 01 [1080p][CHS]"); return r; }
    private JsonObject usage() { return repository.get("upgrade_ai_cache","usage:"+LocalDate.now(ZoneId.of(settings.getTimezone())),JsonObject.class).orElseGet(JsonObject::new); }
    private static JsonObject response(String content,String finish) {
        JsonObject result=new JsonObject(),choice=new JsonObject(),message=new JsonObject(),usage=new JsonObject();
        message.addProperty("content",content); choice.add("message",message); choice.addProperty("finish_reason",finish); JsonArray choices=new JsonArray(); choices.add(choice);
        result.add("choices",choices); usage.addProperty("total_tokens",25); result.add("usage",usage); return result;
    }
    @Test void cacheAvoidsRepeatRequestsAndModelChangeInvalidatesIt() {
        AtomicInteger calls=new AtomicInteger();
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenAnswer(inv->{ calls.incrementAndGet(); return response("{\"items\":[{\"id\":\"r\",\"fields\":[{\"field\":\"resolution\",\"value\":1080,\"quote\":\"1080p\"}]}]}","stop"); });
            Release first=release(); client.analyze(List.of(first)); assertEquals(1080,first.getResolution()); client.analyze(List.of(release())); assertEquals(1,calls.get());
            settings.setAiModel("another-fixture-model"); settings.setAiTestFingerprint(DeepSeekAnalysisClient.fingerprint(settings)); client.analyze(List.of(release())); assertEquals(2,calls.get()); assertEquals(50,usage().get("tokens").getAsLong());
        }
    }
    @Test void exhaustedBudgetBlocksUncachedWorkButKeepsCachedParsingAvailable() {
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenReturn(response("{\"items\":[]}","stop"));
            client.analyze(List.of(release())); settings.setAiDailyRequests(0); assertDoesNotThrow(()->client.analyze(List.of(release())));
            Release other=release(); other.setId("uncached"); assertEquals("AI_DAILY_BUDGET_EXHAUSTED",assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(other))).getMessage());
            assertEquals(1,usage().get("requests").getAsInt());
        }
    }
    @Test void retryAfterAndEmptyOrTruncatedResponsesCannotBecomeCachedSuccess() {
        AtomicInteger calls=new AtomicInteger();
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenAnswer(inv->{ calls.incrementAndGet(); throw new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_429",true,6000); });
            assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(release()))); assertEquals(1,calls.get());
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenReturn(response("{\"items\":[]}","length"));
            assertEquals("AI_TRUNCATED_RESPONSE",assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(release()))).getMessage()); assertEquals(25,usage().get("tokens").getAsInt());
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenReturn(JsonParser.parseString("{\"choices\":[],\"usage\":{\"total_tokens\":10}}").getAsJsonObject());
            assertEquals("AI_EMPTY_RESPONSE",assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(release()))).getMessage());
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenReturn(response("invalid JSON","stop")); assertThrows(RuntimeException.class,()->client.analyze(List.of(release())));
            assertEquals(4,usage().get("requests").getAsInt());
        }
    }
    @Test void retryableFailureRetriesAtMostTwiceAndFiveFailuresOpenCircuit() {
        AtomicInteger calls=new AtomicInteger();
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenAnswer(inv->{ int n=calls.incrementAndGet(); if(n<=2) throw new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_500",true); return response("{\"items\":[]}","stop"); });
            client.analyze(List.of(release())); assertEquals(3,calls.get());
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_UNAVAILABLE",false));
            for(int i=0;i<5;i++) { Release r=release(); r.setId("failure-"+i); assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(r))); }
            Release next=release(); next.setId("after-circuit"); assertEquals("AI_CIRCUIT_OPEN",assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(next))).getMessage());
        }
    }
    @Test void changedCredentialsRequireSelfTestAndAliasesRemainSearchSuggestions() {
        settings.setAiModel("changed-without-test"); assertEquals("AI_CONNECTION_TEST_REQUIRED",assertThrows(UpgradeHttp.SourceFailure.class,()->client.analyze(List.of(release()))).getMessage());
        settings.setAiTestFingerprint(DeepSeekAnalysisClient.fingerprint(settings));
        try(var http=mockStatic(UpgradeHttp.class,CALLS_REAL_METHODS)) {
            http.when(()->UpgradeHttp.execute(any(),anyMap(),any())).thenReturn(response("{\"aliases\":[\"Example Alternate\",\"https://untrusted.example\"],\"subjectId\":999}","stop"));
            var target=new ani.rss.upgrade.UpgradeModels.Target(); target.setSubjectId("100"); target.setTitle("Example");
            var aliases=client.aliases(target); assertTrue(aliases.contains("Example Alternate")); assertFalse(aliases.contains("https://untrusted.example")); assertEquals("100",target.getSubjectId()); assertTrue(target.getAliases().isEmpty());
        }
    }
}
