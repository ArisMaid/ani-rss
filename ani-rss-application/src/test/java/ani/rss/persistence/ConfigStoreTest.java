package ani.rss.persistence;

import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import ani.rss.util.other.ConfigUtil;
import ani.rss.commons.GsonStatic;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotIsDefensiveAndCommitIsDurable() throws Exception {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        ConfigStore store = new ConfigStore(runtime, () -> tempDir.resolve("config.json"), value -> { });

        Config snapshot = store.snapshot();
        snapshot.setDownloadToolHost("http://changed");
        assertNotSame(snapshot, runtime);
        assertEquals(ConfigUtil.CONFIG.getDownloadToolHost(), runtime.getDownloadToolHost());

        Config candidate = ConfigUtil.copy(runtime).setDownloadToolHost("http://new-host");
        store.commit(candidate);
        assertEquals("http://new-host", runtime.getDownloadToolHost());
        assertEquals("http://new-host", Files.readString(tempDir.resolve("config.json"))
                .contains("http://new-host") ? "http://new-host" : "");
    }

    @Test
    void failedCommitRestoresRuntimeAndLeavesDiskUntouched() {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        Path target = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(runtime, () -> target, value -> { },
                (path, content) -> {
                    throw new IOException("injected write failure");
                });

        Config original = ConfigUtil.copy(runtime);
        runtime.setDownloadToolHost("http://uncommitted");
        assertThrows(IllegalStateException.class, store::commitRuntimeCandidate);
        assertEquals(original.getDownloadToolHost(), runtime.getDownloadToolHost());
        assertEquals(original.getDownloadToolType(), runtime.getDownloadToolType());
        assertEquals(false, Files.exists(target));
    }

    @Test
    void retainsUnknownFieldsFromANewerCompatibleConfiguration() throws Exception {
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG);
        defaults.setNotificationConfigList(new ArrayList<>(java.util.List.of(
                NotificationConfig.createNotificationConfig())));
        JsonObject source = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        source.addProperty("futureTopLevel", "preserve-me");
        source.getAsJsonObject("login").addProperty("futureLoginField", true);
        source.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject()
                .addProperty("futureNotificationField", 7);
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, GsonStatic.toJson(source));

        Config runtime = ConfigUtil.copy(defaults);
        ConfigStore store = new ConfigStore(runtime, () -> target, value -> { });
        store.load(defaults);
        store.commit(store.snapshot().setDownloadToolHost("http://updated-host"));

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertEquals("preserve-me", saved.get("futureTopLevel").getAsString());
        assertTrue(saved.getAsJsonObject("login").get("futureLoginField").getAsBoolean());
        assertEquals(7, saved.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject()
                .get("futureNotificationField").getAsInt());
    }

    @Test
    void doesNotReusePreservedFieldsWhenTheConfigurationPathChanges() throws Exception {
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG);
        AtomicReference<Path> currentPath = new AtomicReference<>(tempDir.resolve("first.json"));
        writeFutureDocument(currentPath.get(), defaults, "first");

        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), currentPath::get, value -> { });
        store.load(defaults);
        currentPath.set(tempDir.resolve("second.json"));
        writeFutureDocument(currentPath.get(), defaults, "second");
        store.load(defaults);
        store.commit(store.snapshot());

        JsonObject saved = JsonParser.parseString(Files.readString(currentPath.get())).getAsJsonObject();
        assertEquals("second", saved.get("futureTopLevel").getAsString());
    }

    @Test
    void savesAnUpstreamCompatibleSchemaWithoutLocalOnlyFields() throws Exception {
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG);
        defaults.setNotificationConfigList(new ArrayList<>(java.util.List.of(
                NotificationConfig.createNotificationConfig())));
        Path target = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), () -> target, value -> { });

        store.commit(store.snapshot());

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertTrue(!saved.has("imagePrivateAllowlist"));
        assertTrue(!saved.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject().has("id"));
    }

    @Test
    void acceptsEveryNumericBoundaryEmittedByTheUpstream320Ui() {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        ConfigStore store = new ConfigStore(runtime, () -> tempDir.resolve("config.json"), value -> { });
        Config upstream = ConfigUtil.copy(runtime)
                .setDownloadRetry(100)
                .setLoginEffectiveHours(99_999)
                .setOpenListDownloadTimeout(Integer.MAX_VALUE)
                .setOpenListDownloadRetryNumber(-1L)
                .setConfigBackupDay(Integer.MAX_VALUE);

        Config validated = store.validateCandidate(upstream);

        assertEquals(100, validated.getDownloadRetry());
        assertEquals(99_999, validated.getLoginEffectiveHours());
        assertEquals(Integer.MAX_VALUE, validated.getOpenListDownloadTimeout());
        assertEquals(-1L, validated.getOpenListDownloadRetryNumber());
        assertEquals(Integer.MAX_VALUE, validated.getConfigBackupDay());
    }

    @Test
    void stillRejectsValuesOutsideTheUpstream320Contract() {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        ConfigStore store = new ConfigStore(runtime, () -> tempDir.resolve("config.json"), value -> { });

        assertThrows(IllegalArgumentException.class,
                () -> store.validateCandidate(ConfigUtil.copy(runtime).setDownloadRetry(101)));
        assertThrows(IllegalArgumentException.class,
                () -> store.validateCandidate(ConfigUtil.copy(runtime).setLoginEffectiveHours(0)));
        assertThrows(IllegalArgumentException.class,
                () -> store.validateCandidate(ConfigUtil.copy(runtime).setOpenListDownloadRetryNumber(-2L)));
    }

    @Test
    void removesRetiredLocalFieldsWithoutRemovingUnknownFutureFields() throws Exception {
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG);
        JsonObject document = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        JsonObject notification = GsonStatic.GSON.toJsonTree(NotificationConfig.createNotificationConfig())
                .getAsJsonObject();
        notification.addProperty("id", UUID.randomUUID().toString());
        notification.addProperty("futureNotificationField", "keep");
        com.google.gson.JsonArray notifications = new com.google.gson.JsonArray();
        notifications.add(notification);
        document.add("notificationConfigList", notifications);
        document.addProperty("imagePrivateAllowlist", "127.0.0.1");
        document.addProperty("futureTopLevel", "keep");
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, GsonStatic.toJson(document));

        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), () -> target, value -> { });
        store.load(defaults);

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertTrue(!saved.has("imagePrivateAllowlist"));
        assertEquals("keep", saved.get("futureTopLevel").getAsString());
        JsonObject savedNotification = saved.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject();
        assertTrue(!savedNotification.has("id"));
        assertEquals("keep", savedNotification.get("futureNotificationField").getAsString());
    }

    @Test
    void preservesAnUnknownFutureNotificationIdWithoutLegacyLocalMarker() throws Exception {
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG);
        JsonObject document = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        JsonObject notification = GsonStatic.GSON.toJsonTree(NotificationConfig.createNotificationConfig())
                .getAsJsonObject();
        String futureId = UUID.randomUUID().toString();
        notification.addProperty("id", futureId);
        com.google.gson.JsonArray notifications = new com.google.gson.JsonArray();
        notifications.add(notification);
        document.add("notificationConfigList", notifications);
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, GsonStatic.toJson(document));

        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), () -> target, value -> { });
        store.load(defaults);
        store.commit(store.snapshot());

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertEquals(futureId, saved.getAsJsonArray("notificationConfigList").get(0)
                .getAsJsonObject().get("id").getAsString());
    }

    @Test
    void doesNotAttachFutureFieldsToTheWrongNotificationAfterReordering() throws Exception {
        NotificationConfig first = NotificationConfig.createNotificationConfig()
                .setComment("first").setSort(1L);
        NotificationConfig second = NotificationConfig.createNotificationConfig()
                .setComment("second").setSort(2L);
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG)
                .setNotificationConfigList(new ArrayList<>(java.util.List.of(first, second)));
        JsonObject document = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        document.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject()
                .addProperty("firstFutureField", "first");
        document.getAsJsonArray("notificationConfigList").get(1).getAsJsonObject()
                .addProperty("secondFutureField", "second");
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, GsonStatic.toJson(document));

        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), () -> target, value -> { });
        store.load(defaults);
        Config reordered = store.snapshot().setNotificationConfigList(new ArrayList<>(java.util.List.of(
                second, first)));
        store.commit(reordered);

        JsonObject firstSaved = JsonParser.parseString(Files.readString(target)).getAsJsonObject()
                .getAsJsonArray("notificationConfigList").get(0).getAsJsonObject();
        JsonObject secondSaved = JsonParser.parseString(Files.readString(target)).getAsJsonObject()
                .getAsJsonArray("notificationConfigList").get(1).getAsJsonObject();
        assertEquals("second", firstSaved.get("secondFutureField").getAsString());
        assertEquals("first", secondSaved.get("firstFutureField").getAsString());
    }

    @Test
    void doesNotReuseFutureFieldsForAmbiguousNotifications() throws Exception {
        NotificationConfig first = NotificationConfig.createNotificationConfig()
                .setComment("same").setSort(1L);
        NotificationConfig second = NotificationConfig.createNotificationConfig()
                .setComment("same").setSort(1L);
        Config defaults = ConfigUtil.copy(ConfigUtil.CONFIG)
                .setNotificationConfigList(new ArrayList<>(java.util.List.of(first, second)));
        JsonObject document = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        document.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject()
                .addProperty("futureField", "first-only");
        Path target = tempDir.resolve("config.json");
        Files.writeString(target, GsonStatic.toJson(document));

        ConfigStore store = new ConfigStore(ConfigUtil.copy(defaults), () -> target, value -> { });
        store.load(defaults);
        store.commit(store.snapshot());

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
        assertTrue(!saved.getAsJsonArray("notificationConfigList").get(0).getAsJsonObject()
                .has("futureField"));
        assertTrue(!saved.getAsJsonArray("notificationConfigList").get(1).getAsJsonObject()
                .has("futureField"));
    }

    private static void writeFutureDocument(Path target, Config defaults, String value) throws IOException {
        JsonObject document = GsonStatic.GSON.toJsonTree(defaults).getAsJsonObject();
        document.addProperty("futureTopLevel", value);
        Files.writeString(target, GsonStatic.toJson(document));
    }
}
