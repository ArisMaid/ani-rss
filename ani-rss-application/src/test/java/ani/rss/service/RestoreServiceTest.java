package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.persistence.DatabaseManager;
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

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("CONFIG", tempDir.toString());
        Files.writeString(tempDir.resolve("config.v2.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
        DatabaseManager.close();
        TaskService.LOOP.set(false);
        TaskService.THREADS.clear();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void confirmedRestoreCompletesAndReloadsRuntime() throws Exception {
        byte[] archive = archive("{}", "[]");
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
        Files.writeString(tempDir.resolve("config.v2.json"), "{}", StandardCharsets.UTF_8);
        byte[] archive = archive("{\"downloadRetry\":0}", "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        service.confirm(staged.operationId());

        RestoreService.RestoreOperationView result = await(service, staged.operationId());
        assertEquals(RestoreService.RestoreStatus.ROLLED_BACK, result.status());
        assertEquals("{}",
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
