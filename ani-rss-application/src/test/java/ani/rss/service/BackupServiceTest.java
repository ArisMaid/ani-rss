package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupServiceTest {
    @TempDir
    Path tempDir;
    private Config original;

    @BeforeEach
    void setUp() throws Exception {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Config config = ConfigUtil.copy(original)
                .setLogin(new Login().setUsername("backup-user")
                        .setPassword(AuthService.encodePassword("backup-password")));
        Files.writeString(tempDir.resolve("config.v2.json"),
                GsonStatic.toJson(config), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
        ConfigUtil.load();
        DatabaseManager.close();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        ConfigUtil.sync(original);
        System.clearProperty("CONFIG");
    }

    @Test
    void maximumUpstreamRetentionValueDoesNotOverflowIntoDeletingValidBackups() throws Exception {
        ConfigUtil.sync(ConfigUtil.copy(ConfigUtil.snapshot())
                .setConfigBackupDay(Integer.MAX_VALUE));
        Path backupDir = tempDir.resolve("backup");
        Files.createDirectories(backupDir);
        Path oldest = backupDir.resolve("1970-01-01.zip");
        Files.write(oldest, new byte[]{1});

        new BackupService(new ClearService()).clearBackup();

        assertTrue(Files.exists(oldest));
    }

    @Test
    void ordinaryRetentionStillDeletesOneExpiredBackup() throws Exception {
        ConfigUtil.sync(ConfigUtil.copy(ConfigUtil.snapshot()).setConfigBackupDay(1));
        Path backupDir = tempDir.resolve("backup");
        Files.createDirectories(backupDir);
        Path expired = backupDir.resolve("2000-01-01.zip");
        Files.write(expired, new byte[]{1});

        new BackupService(new ClearService()).clearBackup();

        assertFalse(Files.exists(expired));
    }
}
