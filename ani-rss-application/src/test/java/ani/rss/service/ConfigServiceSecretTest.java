package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.entity.NotificationConfig;
import ani.rss.enums.NotificationTypeEnum;
import ani.rss.enums.OffsetTypeEnum;
import cn.hutool.crypto.SecureUtil;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.info.GitProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void preservesNotificationSecretsByUniquePublicIdentityAfterDeletion() {
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
    void notificationOperationsHydrateMaskedSecretByUniquePublicIdentity() {
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
    void doesNotCopyOneNotificationSecretToTwoIdenticalCandidates() {
        NotificationConfig configuredNotification = NotificationConfig.createNotificationConfig()
                .setTelegramBotToken("operation-secret");
        ConfigUtil.sync(ConfigUtil.copy(original).setNotificationConfigList(List.of(configuredNotification)));
        NotificationConfig first = GsonStatic.fromJson(GsonStatic.toJson(configuredNotification), NotificationConfig.class)
                .setTelegramBotToken("");
        NotificationConfig second = GsonStatic.fromJson(GsonStatic.toJson(configuredNotification), NotificationConfig.class)
                .setTelegramBotToken("");

        new ConfigService().setConfig(ConfigUtil.snapshot().setNotificationConfigList(List.of(first, second)));

        List<NotificationConfig> stored = ConfigUtil.snapshot().getNotificationConfigList();
        assertEquals("operation-secret", stored.get(0).getTelegramBotToken());
        assertEquals("", stored.get(1).getTelegramBotToken());
    }

    @Test
    void masksAstrBotKeyAndRestoresItOnlyForAUniqueIdentity() {
        NotificationConfig saved = NotificationConfig.createNotificationConfig()
                .setNotificationType(NotificationTypeEnum.ASTRBOT)
                .setComment("AstrBot")
                .setAstrBotApiKey("saved-astrbot-key");
        assertEquals("http://127.0.0.1:6185", saved.getAstrBotHost());
        assertEquals("", saved.getAstrBotUmo());
        assertTrue(saved.getAstrBotImage());

        ConfigUtil.sync(ConfigUtil.copy(original).setNotificationConfigList(List.of(saved)));
        ConfigService service = configService();
        Config response = service.config();
        NotificationConfig masked = response.getNotificationConfigList().get(0);
        assertEquals("", masked.getAstrBotApiKey());
        assertEquals("saved-astrbot-key",
                service.notificationForOperation(masked).getAstrBotApiKey());
        assertEquals("", masked.getAstrBotApiKey());

        service.setConfig(response);
        assertEquals("saved-astrbot-key",
                ConfigUtil.snapshot().getNotificationConfigList().get(0).getAstrBotApiKey());

        NotificationConfig duplicate = NotificationConfig.createNotificationConfig()
                .setNotificationType(NotificationTypeEnum.ASTRBOT)
                .setComment("AstrBot")
                .setAstrBotApiKey("other-key");
        ConfigUtil.sync(ConfigUtil.snapshot().setNotificationConfigList(List.of(saved, duplicate)));
        Config ambiguousResponse = service.config();
        NotificationConfig ambiguous = ambiguousResponse.getNotificationConfigList().get(0);
        assertEquals("", service.notificationForOperation(ambiguous).getAstrBotApiKey());
        service.setConfig(ambiguousResponse.setNotificationConfigList(List.of(ambiguous)));
        assertEquals("", ConfigUtil.snapshot().getNotificationConfigList().get(0).getAstrBotApiKey());
    }

    @Test
    void computesDefaultLoginBeforeMaskingAndNeverPersistsTheSubmittedIndicator() throws Exception {
        ConfigUtil.sync(ConfigUtil.copy(original)
                .setOffsetType(null)
                .setLogin(new Login("admin", SecureUtil.md5("admin"))));
        ConfigService service = configService();
        Config response = service.config();
        assertTrue(response.getLogin().getIsDefault());
        assertEquals("", response.getLogin().getPassword());
        assertEquals(OffsetTypeEnum.AUTO_OFFSET, response.getOffsetType());
        assertNull(ConfigUtil.snapshot().getLogin().getIsDefault());

        response.getLogin().setIsDefault(false);
        response.setOffsetType(OffsetTypeEnum.BGM_OFFSET);
        service.setConfig(response);
        assertTrue(service.config().getLogin().getIsDefault());
        assertEquals(OffsetTypeEnum.BGM_OFFSET, service.config().getOffsetType());
        assertNull(ConfigUtil.snapshot().getLogin().getIsDefault());
        assertFalse(Files.readString(tempDir.resolve("config.v2.json")).contains("isDefault"));
    }

    private static ConfigService configService() {
        ConfigService service = new ConfigService();
        ReflectionTestUtils.setField(service, "gitProperties", new GitProperties(new Properties()));
        ReflectionTestUtils.setField(service, "afdianService", mock(AfdianService.class));
        return service;
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
