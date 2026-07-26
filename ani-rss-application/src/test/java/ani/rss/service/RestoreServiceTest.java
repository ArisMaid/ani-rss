package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.auth.AuthService;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreServiceTest {
    @TempDir
    Path tempDir;
    private Config original;
    private String baselineConfig;

    @BeforeEach
    void setUp() throws Exception {
        original = ConfigUtil.snapshot();
        System.setProperty("CONFIG", tempDir.toString());
        Config valid = ConfigUtil.copy(original)
                .setLogin(new Login().setUsername("restore-user")
                        .setPassword(AuthService.encodePassword("restore-password")));
        baselineConfig = GsonStatic.toJson(valid);
        Files.writeString(tempDir.resolve("config.v2.json"), baselineConfig, StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
        DatabaseManager.close();
        TaskService.LOOP.set(false);
        TaskService.THREADS.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        ConfigUtil.sync(original);
        System.clearProperty("CONFIG");
        LogUtil.loadLogback();
    }

    @Test
    void confirmedRestoreCompletesAndReloadsRuntime() throws Exception {
        byte[] archive = archive(baselineConfig, "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), staged.errors()::toString);
        service.confirm(staged.operationId());

        RestoreService.RestoreOperationView result = await(service, staged.operationId());
        assertEquals(RestoreService.RestoreStatus.SUCCEEDED, result.status());
        assertTrue(Files.exists(tempDir.resolve("config.v2.json")));
        service.shutdown();
    }

    @Test
    void failedRuntimeLoadRollsBackOldFiles() throws Exception {
        Config invalid = GsonStatic.fromJson(baselineConfig, Config.class).setDownloadRetry(0);
        byte[] archive = archive(GsonStatic.toJson(invalid), "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        service.confirm(staged.operationId());

        RestoreService.RestoreOperationView result = await(service, staged.operationId());
        assertEquals(RestoreService.RestoreStatus.ROLLED_BACK, result.status());
        assertEquals(baselineConfig,
                Files.readString(tempDir.resolve("config.v2.json")));
        service.shutdown();
    }

    private RestoreService service() {
        TaskCoordinator coordinator = new TaskCoordinator();
        return new RestoreService(new TaskService(coordinator), coordinator);
    }

    private byte[] archive(String config, String subscriptions) throws Exception {
        Path source = tempDir.resolve("source-" + System.nanoTime());
        Files.createDirectories(source);
        Files.writeString(source.resolve("config.v2.json"), config, StandardCharsets.UTF_8);
        Files.writeString(source.resolve("ani.v2.json"), subscriptions, StandardCharsets.UTF_8);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BackupArchive.create(output, source, "3.1.75");
        return output.toByteArray();
    }

    private static RestoreService.RestoreOperationView await(
            RestoreService service, String operationId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        RestoreService.RestoreOperationView view;
        do {
            Thread.sleep(50);
            view = service.status(operationId);
            if (view.status() == RestoreService.RestoreStatus.SUCCEEDED ||
                    view.status() == RestoreService.RestoreStatus.ROLLED_BACK ||
                    view.status() == RestoreService.RestoreStatus.MAINTENANCE_REQUIRED ||
                    view.status() == RestoreService.RestoreStatus.FAILED) {
                return view;
            }
        } while (System.nanoTime() < deadline);
        return service.status(operationId);
    }
}
