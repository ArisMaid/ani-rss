package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.auth.AuthService;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.AniUtil;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void invalidRuntimeCandidateIsRejectedBeforeConfirmation() throws Exception {
        Config invalid = GsonStatic.fromJson(baselineConfig, Config.class).setDownloadRetry(0);
        byte[] archive = archive(GsonStatic.toJson(invalid), "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        assertEquals(RestoreService.RestoreStatus.INVALID, staged.status());
        assertThrows(IllegalStateException.class, () -> service.confirm(staged.operationId()));
        assertEquals(baselineConfig,
                Files.readString(tempDir.resolve("config.v2.json")));
        service.shutdown();
    }

    @Test
    void failuresAtEachRuntimeSwitchCheckpointRollBackOldFiles() throws Exception {
        Config changed = GsonStatic.fromJson(baselineConfig, Config.class).setRssSleepMinutes(17);
        byte[] archive = archive(GsonStatic.toJson(changed), "[]");
        List<RestoreService.Checkpoint> checkpoints = List.of(
                RestoreService.Checkpoint.TASKS_STOPPED,
                RestoreService.Checkpoint.DATABASE_CLOSED,
                RestoreService.Checkpoint.CURRENT_MOVED,
                RestoreService.Checkpoint.STAGED_INSTALLED,
                RestoreService.Checkpoint.DATABASE_REOPENED,
                RestoreService.Checkpoint.CONFIG_LOADED,
                RestoreService.Checkpoint.AUTH_LOADED,
                RestoreService.Checkpoint.SUBSCRIPTIONS_LOADED);

        for (RestoreService.Checkpoint target : checkpoints) {
            Files.writeString(tempDir.resolve("config.v2.json"), baselineConfig, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
            ConfigUtil.load();
            AniUtil.load(false);
            AtomicBoolean first = new AtomicBoolean(true);
            TaskCoordinator coordinator = new TaskCoordinator();
            RestoreService service = new RestoreService(
                    new TaskService(coordinator), coordinator, checkpoint -> {
                if (checkpoint == target && first.getAndSet(false)) {
                    throw new IllegalStateException("injected " + checkpoint);
                }
            });

            RestoreService.RestoreOperationView staged = service.stage(
                    new ByteArrayInputStream(archive), archive.length);
            assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), target.name());
            service.confirm(staged.operationId());
            RestoreService.RestoreOperationView result = await(service, staged.operationId());

            assertEquals(RestoreService.RestoreStatus.ROLLED_BACK, result.status(), target.name());
            assertEquals(baselineConfig, Files.readString(tempDir.resolve("config.v2.json")), target.name());
            assertEquals(TaskCoordinator.State.STOPPED, coordinator.state(), target.name());
            service.shutdown();
        }
    }

    @Test
    void failuresAroundTaskRestartRollBackAndRestorePriorRunningState() throws Exception {
        Config changed = GsonStatic.fromJson(baselineConfig, Config.class).setRssSleepMinutes(18);
        byte[] archive = archive(GsonStatic.toJson(changed), "[]");

        for (RestoreService.Checkpoint target : List.of(
                RestoreService.Checkpoint.BEFORE_TASK_RESTART,
                RestoreService.Checkpoint.AFTER_TASK_RESTART)) {
            Files.writeString(tempDir.resolve("config.v2.json"), baselineConfig, StandardCharsets.UTF_8);
            Files.writeString(tempDir.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
            ConfigUtil.load();
            AniUtil.load(false);
            AtomicBoolean first = new AtomicBoolean(true);
            TaskCoordinator coordinator = new TaskCoordinator();
            TrackingTaskService tasks = new TrackingTaskService(coordinator, true, true);
            RestoreService service = new RestoreService(tasks, coordinator, checkpoint -> {
                if (checkpoint == target && first.getAndSet(false)) {
                    throw new IllegalStateException("injected " + checkpoint);
                }
            });

            RestoreService.RestoreOperationView staged = service.stage(
                    new ByteArrayInputStream(archive), archive.length);
            service.confirm(staged.operationId());
            RestoreService.RestoreOperationView result = await(service, staged.operationId());

            assertEquals(RestoreService.RestoreStatus.ROLLED_BACK, result.status(), target.name());
            assertEquals(baselineConfig, Files.readString(tempDir.resolve("config.v2.json")), target.name());
            assertEquals(TaskCoordinator.State.RUNNING, coordinator.state(), target.name());
            assertTrue(tasks.stopCalls >= 2, target.name());
            assertEquals(target == RestoreService.Checkpoint.BEFORE_TASK_RESTART ? 1 : 2,
                    tasks.startCalls, target.name());
            service.shutdown();
        }
    }

    @Test
    void rollbackConflictLeavesMaintenanceRequiredAndTasksDisabled() throws Exception {
        Config changed = GsonStatic.fromJson(baselineConfig, Config.class).setRssSleepMinutes(20);
        byte[] archive = archive(GsonStatic.toJson(changed), "[]");
        AtomicReference<String> operationId = new AtomicReference<>();
        AtomicBoolean first = new AtomicBoolean(true);
        TaskCoordinator coordinator = new TaskCoordinator();
        RestoreService service = new RestoreService(
                new TaskService(coordinator), coordinator, checkpoint -> {
            if (checkpoint == RestoreService.Checkpoint.STAGED_INSTALLED && first.getAndSet(false)) {
                try {
                    Path conflict = tempDir.resolve("restore/rollback")
                            .resolve(operationId.get())
                            .resolve("failed/config.v2.json");
                    Files.writeString(conflict, "conflict", StandardCharsets.UTF_8);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                throw new IllegalStateException("force rollback");
            }
        });

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        operationId.set(staged.operationId());
        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.MAINTENANCE_REQUIRED, result.status());
        assertEquals(TaskCoordinator.State.FAILED, coordinator.state());
        assertEquals(2, result.errors().size());
        service.shutdown();
    }

    @Test
    void taskStopTimeoutLeavesMaintenanceRequiredAndDoesNotRestartTasks() throws Exception {
        byte[] archive = archive(baselineConfig, "[]");
        TaskCoordinator coordinator = new TaskCoordinator();
        TrackingTaskService tasks = new TrackingTaskService(coordinator, true, false);
        RestoreService service = new RestoreService(tasks, coordinator);

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.MAINTENANCE_REQUIRED, result.status());
        assertEquals(TaskCoordinator.State.FAILED, coordinator.state());
        assertEquals(0, tasks.startCalls);
        assertTrue(tasks.stopCalls >= 3);
        service.shutdown();
    }

    @Test
    void taskRestartFailureStopsPartialRuntimeAndRollsBack() throws Exception {
        Config changed = GsonStatic.fromJson(baselineConfig, Config.class).setRssSleepMinutes(19);
        byte[] archive = archive(GsonStatic.toJson(changed), "[]");
        TaskCoordinator coordinator = new TaskCoordinator();
        RestartFailingTaskService tasks = new RestartFailingTaskService(coordinator);
        RestoreService service = new RestoreService(tasks, coordinator);

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.ROLLED_BACK, result.status());
        assertEquals(baselineConfig, Files.readString(tempDir.resolve("config.v2.json")));
        assertTrue(tasks.stopCalls >= 2);
        assertEquals(2, tasks.startCalls);
        assertEquals(TaskCoordinator.State.RUNNING, coordinator.state());
        service.shutdown();
    }

    private RestoreService service() {
        TaskCoordinator coordinator = new TaskCoordinator();
        return new RestoreService(new TaskService(coordinator), coordinator);
    }

    private static final class RestartFailingTaskService extends TaskService {
        private boolean running = true;
        private int stopCalls;
        private int startCalls;

        private RestartFailingTaskService(TaskCoordinator coordinator) {
            super(coordinator);
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public synchronized boolean stop(Duration timeout) {
            stopCalls++;
            running = false;
            return true;
        }

        @Override
        public synchronized void start() {
            startCalls++;
            if (startCalls == 1) {
                running = true;
                throw new IllegalStateException("simulated partial task restart");
            }
            running = true;
        }
    }

    private static final class TrackingTaskService extends TaskService {
        private boolean running;
        private final boolean stopSucceeds;
        private int stopCalls;
        private int startCalls;

        private TrackingTaskService(
                TaskCoordinator coordinator, boolean running, boolean stopSucceeds) {
            super(coordinator);
            this.running = running;
            this.stopSucceeds = stopSucceeds;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public synchronized boolean stop(Duration timeout) {
            stopCalls++;
            if (stopSucceeds) {
                running = false;
            }
            return stopSucceeds;
        }

        @Override
        public synchronized void start() {
            startCalls++;
            running = true;
        }
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
