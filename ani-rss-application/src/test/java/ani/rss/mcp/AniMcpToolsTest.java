package ani.rss.mcp;

import ani.rss.commons.GsonStatic;
import ani.rss.controller.AniController;
import ani.rss.entity.Ani;
import ani.rss.entity.web.Result;
import ani.rss.mcp.dto.DeleteSubscriptionDTO;
import ani.rss.service.SubscriptionDeletionService;
import ani.rss.util.other.AniUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AniMcpToolsTest {
    private final AniMcpTools tools = new AniMcpTools();
    private final AniController controller = mock(AniController.class);
    private final SubscriptionDeletionService deletion = mock(SubscriptionDeletionService.class);
    private List<Ani> original;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tools, "aniController", controller);
        ReflectionTestUtils.setField(tools, "subscriptionDeletionService", deletion);
        original = new ArrayList<>(AniUtil.ANI_LIST);
        AniUtil.ANI_LIST.clear();
        AniUtil.ANI_LIST.add(new Ani().setId("one").setTitle("Original")
                .setUrl("https://example.test/rss").setSeason(1).setOffset(0)
                .setQualityPolicyId("policy").setCurrentEpisodeNumber(7)
                .setJpTitle("日本語").setCustomDownloadPathTemplate("/kept"));
    }

    @AfterEach
    void tearDown() {
        AniUtil.ANI_LIST.clear();
        AniUtil.ANI_LIST.addAll(original);
    }

    @Test
    void partialEditPreservesForkFieldsAndSupportsClearingAndLegacyAlias() {
        when(controller.setAniFromMcp(any())).thenReturn(Result.success());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("id", "one");
        changes.put("title", "Changed");
        changes.put("downloadPath", "/changed");
        changes.put("jpTitle", "");
        tools.editSubscription(changes);

        ArgumentCaptor<Ani> captured = ArgumentCaptor.forClass(Ani.class);
        verify(controller).setAniFromMcp(captured.capture());
        Ani updated = captured.getValue();
        assertEquals("Changed", updated.getTitle());
        assertEquals("policy", updated.getQualityPolicyId());
        assertEquals(7, updated.getCurrentEpisodeNumber());
        assertEquals("/changed", updated.getCustomDownloadPathTemplate());
        assertEquals("", updated.getJpTitle());
    }

    @Test
    void missingSubscriptionOrControllerRejectionCannotReportSuccess() {
        assertThrows(IllegalArgumentException.class, () -> tools.editSubscription(Map.of("id", "missing")));
        verify(controller, never()).setAniFromMcp(any());
        when(controller.setAniFromMcp(any())).thenReturn(Result.error("订阅标题重复"));
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> tools.editSubscription(Map.of("id", "one", "title", "Duplicate")));
        assertTrue(rejected.getMessage().contains("订阅标题重复"));
    }

    @Test
    void nullInputKeepsTheExistingField() {
        when(controller.setAniFromMcp(any())).thenReturn(Result.success());
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("id", "one");
        changes.put("jpTitle", null);
        tools.editSubscription(changes);
        ArgumentCaptor<Ani> captured = ArgumentCaptor.forClass(Ani.class);
        verify(controller).setAniFromMcp(captured.capture());
        assertEquals("日本語", captured.getValue().getJpTitle());
    }

    @Test
    void fullMcpObjectKeepsLocalReleaseAndTmdbDatesAcrossUtcSerialization() {
        when(controller.setAniFromMcp(any())).thenReturn(Result.success());
        long instant = LocalDate.of(2026, 4, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        tools.editSubscription(Map.of("id", "one", "releaseDate", instant,
                "tmdb", Map.of("date", java.time.Instant.ofEpochMilli(instant).toString())));
        ArgumentCaptor<Ani> captured = ArgumentCaptor.forClass(Ani.class);
        verify(controller).setAniFromMcp(captured.capture());
        var json = GsonStatic.GSON.toJsonTree(captured.getValue()).getAsJsonObject();
        assertEquals("2026-04-15", json.get("releaseDate").getAsString());
        assertEquals("2026-04-15", json.getAsJsonObject("tmdb").get("date").getAsString());
    }

    @Test
    void partialTmdbEditPreservesUnchangedMetadata() {
        AniUtil.ANI_LIST.get(0).setTmdb(new wushuo.tmdb.api.entity.Tmdb()
                .setId("existing-id").setOverview("existing-overview"));
        when(controller.setAniFromMcp(any())).thenReturn(Result.success());
        tools.editSubscription(Map.of("id", "one", "tmdb", Map.of("name", "new name")));
        ArgumentCaptor<Ani> captured = ArgumentCaptor.forClass(Ani.class);
        verify(controller).setAniFromMcp(captured.capture());
        assertEquals("new name", captured.getValue().getTmdb().getName());
        assertEquals("existing-id", captured.getValue().getTmdb().getId());
        assertEquals("existing-overview", captured.getValue().getTmdb().getOverview());
    }

    @Test
    void deletionDefaultsToKeepingFilesAndExplicitTrueReachesOwnershipService() {
        assertEquals(List.of("one"), tools.deleteSubscription(
                new DeleteSubscriptionDTO().setIds(List.of(" one ", "one"))));
        verify(deletion).delete(List.of("one"), false);
        tools.deleteSubscription(new DeleteSubscriptionDTO().setIds(List.of("one")).setDeleteFiles(true));
        verify(deletion).delete(List.of("one"), true);
        assertThrows(IllegalArgumentException.class,
                () -> tools.deleteSubscription(new DeleteSubscriptionDTO().setIds(List.of(" "))));
        verifyNoMoreInteractions(deletion);
    }
}
