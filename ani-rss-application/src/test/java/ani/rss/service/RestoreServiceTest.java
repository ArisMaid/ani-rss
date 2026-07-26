package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.auth.AuthService;
import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.Login;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ZipUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreServiceTest {
    private static final Set<String> UPSTREAM_ROOT_FILES = Set.of(
            "config.v2.json", "ani.v2.json", "database.db");
    private static final Set<String> UPSTREAM_ROOT_DIRECTORIES = Set.of("files", "torrents");

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

    @Test
    void restoreWithoutForkSidecarsResetsStaleLocalStateAndKeepsRollbackCopies() throws Exception {
        DatabaseManager.withConnection(connection -> {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stale_restore_marker(value TEXT)");
            }
            return null;
        });
        DatabaseManager.close();
        Files.writeString(tempDir.resolve("auth-state.v2.json"),
                "{\"sentinel\":\"stale-auth-state\"}", StandardCharsets.UTF_8);
        Files.createDirectories(tempDir.resolve("torrents/old"));
        Files.writeString(tempDir.resolve("torrents/old/stale.torrent"), "stale", StandardCharsets.UTF_8);
        byte[] archive = archive(baselineConfig, "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), staged.errors()::toString);
        assertTrue(staged.warnings().stream().anyMatch(value -> value.contains("no database")));
        assertTrue(staged.warnings().stream().anyMatch(value -> value.contains("no private authentication")));
        assertTrue(staged.warnings().stream().anyMatch(value -> value.contains("no torrent cache")));

        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.SUCCEEDED, result.status(), result.errors()::toString);
        boolean staleTablePresent = DatabaseManager.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='stale_restore_marker'");
                 var rows = statement.executeQuery()) {
                return rows.next();
            }
        });
        assertFalse(staleTablePresent);
        Path authState = tempDir.resolve("auth-state.v2.json");
        assertFalse(Files.exists(authState) && Files.readString(authState).contains("stale-auth-state"));
        assertFalse(Files.exists(tempDir.resolve("torrents/old/stale.torrent")));
        Path rollback = tempDir.resolve("restore/rollback").resolve(staged.operationId()).resolve("current");
        assertTrue(Files.exists(rollback.resolve("database.db")));
        assertTrue(Files.exists(rollback.resolve("auth-state.v2.json")));
        assertTrue(Files.exists(rollback.resolve("torrents/old/stale.torrent")));
        service.shutdown();
    }

    @Test
    void restoresUpstreamLegacyArchiveWithDocumentedBoundaryValuesAndUnicodePaths() throws Exception {
        Config upstream = GsonStatic.fromJson(baselineConfig, Config.class)
                .setLogin(new Login().setUsername("upstream-user")
                        .setPassword("0123456789abcdef0123456789abcdef"))
                .setDownloadRetry(100)
                .setLoginEffectiveHours(99_999)
                .setOpenListDownloadTimeout(Integer.MAX_VALUE)
                .setOpenListDownloadRetryNumber(-1L)
                .setConfigBackupDay(Integer.MAX_VALUE);
        byte[] archive = legacyArchive(GsonStatic.toJson(upstream), "[]");
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), staged.errors()::toString);
        assertTrue(staged.legacy());

        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.SUCCEEDED, result.status(), result.errors()::toString);
        assertEquals(100, ConfigUtil.snapshot().getDownloadRetry());
        assertEquals(99_999, ConfigUtil.snapshot().getLoginEffectiveHours());
        assertEquals(-1L, ConfigUtil.snapshot().getOpenListDownloadRetryNumber());
        assertTrue(Files.exists(tempDir.resolve(
                "torrents/\u4e2d\u6587\u4f5c\u54c1/Season 1/empty.torrent")));
        assertEquals("cover", Files.readString(tempDir.resolve(
                "files/\u4e2d/\u5c01\u9762.jpg")));
        service.shutdown();
    }

    @Test
    void migratesUpstreamRenameCacheDatabaseAndExportsItBackForLegacyUse() throws Exception {
        Path legacyDatabase = tempDir.resolve("legacy-upstream.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + legacyDatabase);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE RENAME_CACHES (K TEXT PRIMARY KEY, V TEXT NOT NULL)");
            statement.execute("INSERT INTO RENAME_CACHES(K, V) VALUES ('legacy-key', 'legacy-value')");
        }
        byte[] archive = legacyArchive(baselineConfig, "[]", legacyDatabase);
        RestoreService service = service();

        RestoreService.RestoreOperationView staged = service.stage(
                new ByteArrayInputStream(archive), archive.length);
        assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), staged.errors()::toString);
        assertTrue(staged.legacy());
        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.SUCCEEDED, result.status(), result.errors()::toString);
        assertEquals("legacy-value", DatabaseManager.withConnection(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT V FROM RENAME_CACHES WHERE K = 'legacy-key'");
                 var rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }));
        boolean migrated = DatabaseManager.withConnection(connection -> {
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                return rows.next() && rows.getInt(1) >= 8;
            }
        });
        assertTrue(migrated);

        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        new BackupService(new ClearService()).backup(exported);
        Path localExport = tempDir.resolve("legacy-database-local-export.zip");
        Files.write(localExport, exported.toByteArray());
        Path upstreamTarget = tempDir.resolve("legacy-database-upstream-target");
        upstreamImport(localExport, upstreamTarget);

        Path exportedDatabase = upstreamTarget.resolve("database.db");
        assertTrue(DatabaseManager.integrityCheck(exportedDatabase));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + exportedDatabase);
             var statement = connection.prepareStatement(
                     "SELECT V FROM RENAME_CACHES WHERE K = 'legacy-key'");
             var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals("legacy-value", rows.getString(1));
        }
        service.shutdown();
    }

    @Test
    void importsConfiguredRealUpstreamBackupSampleEndToEnd() throws Exception {
        String configured = System.getProperty("ani.rss.upstreamBackupSample");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "set -Dani.rss.upstreamBackupSample to run the external compatibility fixture");
        Path sample = Path.of(configured).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(sample), "configured upstream backup sample is missing");
        Path originalTarget = tempDir.resolve("upstream-original-target");
        upstreamImport(sample, originalTarget);
        Map<String, PayloadFile> originalPayload = upstreamPayload(originalTarget);
        assertEquals(157, originalPayload.size());
        String zeroLengthTorrent = "torrents/M/"
                + "\u540d\u4fa6\u63a2\u5149\u4e4b\u7f8e\u5c11\u5973\uff01 (2026)/Season 1/"
                + "0be95825d32a5a8086476a6ebe175875e5a530cb.torrent";
        assertTrue(originalPayload.containsKey(zeroLengthTorrent));
        assertEquals(0L, originalPayload.get(zeroLengthTorrent).size());
        assertTrue(originalPayload.keySet().stream()
                .anyMatch(path -> path.contains("YUME\u221eMITA (2026)")));
        RestoreService service = service();

        RestoreService.RestoreOperationView staged;
        try (InputStream input = Files.newInputStream(sample)) {
            staged = service.stage(input, Files.size(sample));
        }
        assertEquals(RestoreService.RestoreStatus.VALIDATED, staged.status(), staged.errors()::toString);
        assertTrue(staged.legacy());

        service.confirm(staged.operationId());
        RestoreService.RestoreOperationView result = await(service, staged.operationId());

        assertEquals(RestoreService.RestoreStatus.SUCCEEDED, result.status(), result.errors()::toString);
        assertEquals(28, AniUtil.snapshot().size());
        assertEquals(99_999, ConfigUtil.snapshot().getLoginEffectiveHours());
        assertEquals(originalPayload, payloadForPaths(tempDir, originalPayload.keySet()));
        try (var paths = Files.walk(tempDir.resolve("torrents"))) {
            assertEquals(127, paths.filter(Files::isRegularFile).count());
        }
        try (var paths = Files.walk(tempDir.resolve("files"))) {
            assertEquals(28, paths.filter(Files::isRegularFile).count());
        }

        ByteArrayOutputStream exported = new ByteArrayOutputStream();
        new BackupService(new ClearService()).backup(exported);
        Path localExport = tempDir.resolve("local-export.zip");
        Files.write(localExport, exported.toByteArray());
        Path upstreamTarget = tempDir.resolve("upstream-import-target");
        upstreamImport(localExport, upstreamTarget);

        Config upstreamConfig = GsonStatic.fromJson(
                Files.readString(upstreamTarget.resolve("config.v2.json")), Config.class);
        assertTrue(AuthService.isCompatibleMd5Password(upstreamConfig.getLogin().getPassword()));
        assertEquals(28, GsonStatic.fromJsonList(
                Files.readString(upstreamTarget.resolve("ani.v2.json")), ani.rss.entity.Ani.class).size());
        assertTrue(Files.exists(upstreamTarget.resolve("manifest.json")));
        assertEquals(originalPayload, payloadForPaths(upstreamTarget, originalPayload.keySet()));
        try (var paths = Files.walk(upstreamTarget.resolve("torrents"))) {
            assertEquals(127, paths.filter(Files::isRegularFile).count());
        }
        try (var paths = Files.walk(upstreamTarget.resolve("files"))) {
            assertEquals(28, paths.filter(Files::isRegularFile).count());
        }

        Path upstreamReexport = tempDir.resolve("upstream-reexport.zip");
        upstreamExport(upstreamTarget, upstreamReexport);
        Path upstreamReexportTarget = tempDir.resolve("upstream-reexport-target");
        upstreamImport(upstreamReexport, upstreamReexportTarget);
        assertEquals(originalPayload,
                payloadForPaths(upstreamReexportTarget, originalPayload.keySet()));

        RestoreService.RestoreOperationView restaged;
        try (InputStream input = Files.newInputStream(upstreamReexport)) {
            restaged = service.stage(input, Files.size(upstreamReexport));
        }
        assertEquals(RestoreService.RestoreStatus.VALIDATED,
                restaged.status(), restaged.errors()::toString);
        assertTrue(restaged.legacy());
        service.confirm(restaged.operationId());
        RestoreService.RestoreOperationView restoredAgain = await(service, restaged.operationId());
        assertEquals(RestoreService.RestoreStatus.SUCCEEDED,
                restoredAgain.status(), restoredAgain.errors()::toString);
        assertEquals(originalPayload, payloadForPaths(tempDir, originalPayload.keySet()));
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

    private byte[] legacyArchive(String config, String subscriptions) throws Exception {
        return legacyArchive(config, subscriptions, null);
    }

    private byte[] legacyArchive(String config, String subscriptions, Path database) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "config.v2.json", config.getBytes(StandardCharsets.UTF_8));
            put(zip, "ani.v2.json", subscriptions.getBytes(StandardCharsets.UTF_8));
            if (database != null) {
                put(zip, "database.db", Files.readAllBytes(database));
            }
            put(zip, "torrents/\u4e2d\u6587\u4f5c\u54c1/Season 1/empty.torrent", new byte[0]);
            put(zip, "files/\u4e2d/\u5c01\u9762.jpg", "cover".getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static void upstreamImport(Path archive, Path destination) throws Exception {
        try (InputStream input = Files.newInputStream(archive)) {
            ZipUtil.unzip(input, destination.toFile(), StandardCharsets.UTF_8);
        }
    }

    private static void upstreamExport(Path source, Path archive) throws Exception {
        List<File> backupFiles = Stream.of(
                        "files", "torrents", "database.db", AniUtil.FILE_NAME, ConfigUtil.FILE_NAME)
                .map(name -> source.resolve(name).toFile())
                .filter(File::exists)
                .toList();
        try (OutputStream output = Files.newOutputStream(archive)) {
            ZipUtil.zip(output, StandardCharsets.UTF_8, true, pathname -> {
                if (pathname.isFile()) {
                    return !pathname.getName().startsWith(".");
                }
                return !ArrayUtil.isEmpty(FileUtils.listFiles(pathname));
            }, backupFiles.toArray(File[]::new));
        }
    }

    private static Map<String, PayloadFile> upstreamPayload(Path root) throws Exception {
        Map<String, PayloadFile> result = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (isUpstreamPayload(relative)) {
                    result.put(relative, payloadFile(path));
                }
            }
        }
        return result;
    }

    private static Map<String, PayloadFile> payloadForPaths(Path root, Set<String> paths) throws Exception {
        Map<String, PayloadFile> result = new LinkedHashMap<>();
        for (String relative : paths.stream().sorted().toList()) {
            Path file = root.resolve(relative);
            assertTrue(Files.isRegularFile(file), () -> "missing restored payload: " + relative);
            result.put(relative, payloadFile(file));
        }
        return result;
    }

    private static boolean isUpstreamPayload(String relative) {
        if (UPSTREAM_ROOT_FILES.contains(relative)) {
            return true;
        }
        int slash = relative.indexOf('/');
        return slash > 0 && UPSTREAM_ROOT_DIRECTORIES.contains(relative.substring(0, slash));
    }

    private static PayloadFile payloadFile(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return new PayloadFile(Files.size(file), HexFormat.of().formatHex(digest.digest()));
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
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

    private record PayloadFile(long size, String sha256) {
    }
}
