package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class ConfigServiceSecretTest {
    @TempDir
    Path tempDir;
    private Config original;

    @BeforeEach
    void setUp() {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
    }

    @AfterEach
    void tearDown() {
        ConfigUtil.sync(original);
        System.clearProperty("CONFIG");
        LogUtil.loadLogback();
    }

    @Test
    void preservesNotificationSecretsByStableIdAfterDeletion() {
        NotificationConfig first = NotificationConfig.createNotificationConfig()
                .setComment("first")
                .setTelegramBotToken("first-secret");
        NotificationConfig second = NotificationConfig.createNotificationConfig()
                .setComment("second")
                .setTelegramBotToken("second-secret")
                .setWebHookHeader("Authorization: Bearer webhook-secret")
                .setBarkDeviceKeys(List.of("bark-secret"));
        Config configured = ConfigUtil.copy(original)
                .setNotificationConfigList(List.of(first, second));
        ConfigUtil.sync(configured);

        NotificationConfig maskedSecond = ConfigUtil.copy(ConfigUtil.snapshot())
                .getNotificationConfigList().get(1)
                .setTelegramBotToken("")
                .setWebHookHeader("")
                .setBarkDeviceKeys(List.of());
        Config candidate = ConfigUtil.snapshot()
                .setNotificationConfigList(List.of(maskedSecond));

        new ConfigService().setConfig(candidate);

        List<NotificationConfig> stored = ConfigUtil.snapshot().getNotificationConfigList();
        assertEquals(1, stored.size());
        assertEquals("second", stored.get(0).getComment());
        assertEquals("second-secret", stored.get(0).getTelegramBotToken());
        assertEquals("Authorization: Bearer webhook-secret", stored.get(0).getWebHookHeader());
        assertEquals(List.of("bark-secret"), stored.get(0).getBarkDeviceKeys());
    }

    @Test
    void notificationOperationsHydrateMaskedSecretByStableId() {
        NotificationConfig configuredNotification = NotificationConfig.createNotificationConfig()
                .setTelegramBotToken("operation-secret");
        Config configured = ConfigUtil.copy(original)
                .setNotificationConfigList(List.of(configuredNotification));
        ConfigUtil.sync(configured);
        NotificationConfig masked = GsonStatic.fromJson(
                GsonStatic.toJson(configuredNotification), NotificationConfig.class)
                .setTelegramBotToken("");

        NotificationConfig hydrated = new ConfigService().notificationForOperation(masked);

        assertEquals("operation-secret", hydrated.getTelegramBotToken());
    }

    @Test
    void normalizedCandidateRemainsTheRuntimeSnapshot() {
        Config configured = ConfigUtil.copy(original).setNotificationTemplate("before");
        ConfigUtil.sync(configured);
        Config candidate = ConfigUtil.snapshot().setNotificationTemplate("  normalized  ");

        new ConfigService().setConfig(candidate);

        assertEquals("normalized", ConfigUtil.snapshot().getNotificationTemplate());
    }

    @Test
    void runtimeReloadFailureRollsBackDiskAndMemory() throws Exception {
        Config configured = ConfigUtil.copy(original);
        ConfigUtil.sync(configured);
        int oldInterval = configured.getRssSleepMinutes();
        Config candidate = ConfigUtil.snapshot().setRssSleepMinutes(oldInterval + 1);
        TaskService taskService = mock(TaskService.class);
        doThrow(new IllegalStateException("restart failed"))
                .doNothing().when(taskService).restart();
        ConfigService service = new ConfigService();
        ReflectionTestUtils.setField(service, "taskService", taskService);

        assertThrows(IllegalStateException.class, () -> service.setConfig(candidate));

        assertEquals(oldInterval, ConfigUtil.snapshot().getRssSleepMinutes());
        Config stored = GsonStatic.fromJson(
                Files.readString(tempDir.resolve("config.v2.json")), Config.class);
        assertEquals(oldInterval, stored.getRssSleepMinutes());
    }

    @Test
    void apiKeyRotationUsesServerEntropyAndPersistsImmediately() {
        Config configured = ConfigUtil.copy(original).setApiKey("old-api-key");
        ConfigUtil.sync(configured);

        String rotated = new ConfigService().rotateApiKey();

        assertNotEquals("old-api-key", rotated);
        assertTrue(rotated.length() >= 43);
        assertEquals(rotated, ConfigUtil.snapshot().getApiKey());
    }
}
