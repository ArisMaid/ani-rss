package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.auth.AuthService;
import ani.rss.backup.BackupManifest;
import ani.rss.backup.BackupValidation;
import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class RestoreService {
    private static final Duration MAINTENANCE_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> SWITCH_NAMES = List.of(
            "config.v2.json", "ani.v2.json", "database.db", "auth-state.v2.json", "files", "torrents");
    private static final Set<String> RESET_WHEN_ABSENT = Set.of(
            "database.db", "auth-state.v2.json", "torrents");

    private final TaskService taskService;
    private final TaskCoordinator taskCoordinator;
    private final Map<String, Context> operations = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final FailureInjector failureInjector;

    @Autowired
    public RestoreService(TaskService taskService, TaskCoordinator taskCoordinator) {
        this(taskService, taskCoordinator, checkpoint -> { });
    }

    RestoreService(
            TaskService taskService,
            TaskCoordinator taskCoordinator,
            FailureInjector failureInjector) {
        this.taskService = taskService;
        this.taskCoordinator = taskCoordinator;
        this.failureInjector = failureInjector;
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "ani-rss-restore-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public RestoreOperationView stage(InputStream input, long declaredSize) {
        String operationId = UUID.randomUUID().toString();
        Context context = new Context(operationId);
        operations.put(operationId, context);
        try {
            prepareDirectories(context);
            if (declaredSize > BackupArchive.MAX_COMPRESSED_BYTES) {
                throw new IOException("compressed archive exceeds 50 MiB");
            }
            copyUpload(input, context.uploadPath);
            if (Files.size(context.uploadPath) > BackupArchive.MAX_COMPRESSED_BYTES) {
                throw new IOException("compressed archive exceeds 50 MiB");
            }
            context.validation = BackupArchive.validateAndExtract(
                    context.uploadPath, context.extractedPath);
            validateRuntimeCandidates(context.extractedPath);
            context.warnings = compatibilityWarnings(context.validation);
            context.files = context.validation.files();
            context.status = RestoreStatus.VALIDATED;
        } catch (Exception e) {
            context.status = RestoreStatus.INVALID;
            context.errors = List.of(message(e));
        }
        persist(context);
        return context.view();
    }

    public RestoreOperationView confirm(String operationId) {
        Context context = require(operationId);
        synchronized (context) {
            if (context.status != RestoreStatus.VALIDATED) {
                throw new IllegalStateException("restore operation is not ready for confirmation");
            }
            context.status = RestoreStatus.QUEUED;
            persist(context);
            CompletableFuture.runAsync(() -> execute(context), executor);
            return context.view();
        }
    }

    public RestoreOperationView status(String operationId) {
        Context context = require(operationId);
        synchronized (context) {
            return context.view();
        }
    }

    private void execute(Context context) {
        boolean tasksWereRunning = taskService.isRunning();
        String authFingerprint = null;
        TaskCoordinator.MaintenanceLease lease = null;
        try {
            authFingerprint = AuthService.credentialFingerprint();
            lease = taskCoordinator.enterMaintenance(MAINTENANCE_TIMEOUT);
            transition(context, RestoreStatus.STOPPING);
            if (!taskService.stop(MAINTENANCE_TIMEOUT)) {
                throw new IllegalStateException("background tasks did not stop within 30 seconds");
            }
            checkpoint(Checkpoint.TASKS_STOPPED);
            DatabaseManager.close();
            checkpoint(Checkpoint.DATABASE_CLOSED);
            transition(context, RestoreStatus.SWITCHING);

            Path currentRoot = rollbackRoot(context).resolve("current");
            Path failedRoot = rollbackRoot(context).resolve("failed");
            Files.createDirectories(currentRoot);
            Files.createDirectories(failedRoot);
            for (String name : SWITCH_NAMES) {
                boolean supplied = context.validation.topLevelNames().contains(topLevel(name));
                if (!supplied && !RESET_WHEN_ABSENT.contains(name)) {
                    continue;
                }
                Path current = child(configRoot(), name);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    context.currentMoved.add(name);
                    persist(context);
                    moveExact(current, currentRoot.resolve(name));
                    checkpoint(Checkpoint.CURRENT_MOVED);
                } else if (!supplied) {
                    context.absentBefore.add(name);
                    persist(context);
                }
                Path staged = child(context.extractedPath, name);
                if (supplied && Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    context.installed.add(name);
                    persist(context);
                    moveExact(staged, current);
                    checkpoint(Checkpoint.STAGED_INSTALLED);
                }
            }

            reopenAndLoad(authFingerprint, true);
            if (tasksWereRunning) {
                checkpoint(Checkpoint.BEFORE_TASK_RESTART);
                taskService.start();
                checkpoint(Checkpoint.AFTER_TASK_RESTART);
            }
            lease.complete(tasksWereRunning);
            transition(context, RestoreStatus.SUCCEEDED);
        } catch (IOException | RuntimeException failure) {
            context.errors = List.of(message(failure));
            boolean rolledBack = false;
            if (lease != null) {
                try {
                    ensureTasksStoppedForRollback();
                    rollback(context);
                    reopenAndLoad(authFingerprint, false);
                    if (tasksWereRunning) {
                        taskService.start();
                    }
                    lease.complete(tasksWereRunning);
                    transition(context, RestoreStatus.ROLLED_BACK);
                    rolledBack = true;
                } catch (Exception rollbackFailure) {
                    stopTasksBestEffort();
                    context.errors = List.of(message(failure), message(rollbackFailure));
                    lease.fail();
                    transitionBestEffort(context, RestoreStatus.MAINTENANCE_REQUIRED);
                }
            } else {
                transitionBestEffort(context, RestoreStatus.FAILED);
            }
            if (!rolledBack) {
                log.error("restore operation {} failed", context.operationId, failure);
            }
        } finally {
            if (lease != null) {
                lease.close();
            }
        }
    }

    private void rollback(Context context) throws IOException {
        DatabaseManager.close();
        Path currentRoot = rollbackRoot(context).resolve("current");
        Path failedRoot = rollbackRoot(context).resolve("failed");
        for (int i = context.installed.size() - 1; i >= 0; i--) {
            String name = context.installed.get(i);
            Path current = child(configRoot(), name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(current, failedRoot.resolve(name));
            }
        }
        for (int i = context.currentMoved.size() - 1; i >= 0; i--) {
            String name = context.currentMoved.get(i);
            Path current = child(configRoot(), name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(current, failedRoot.resolve(name));
            }
            Path backup = child(currentRoot, name);
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(backup, current);
            }
        }
        for (int i = context.copiedSnapshots.size() - 1; i >= 0; i--) {
            String name = context.copiedSnapshots.get(i);
            Path current = child(configRoot(), name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(current, failedRoot.resolve(name));
            }
            Path backup = child(currentRoot, name);
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("rollback snapshot is missing: " + name);
            }
            moveExact(backup, current);
        }
        for (int i = context.absentBefore.size() - 1; i >= 0; i--) {
            String name = context.absentBefore.get(i);
            Path current = child(configRoot(), name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(current, failedRoot.resolve(name));
            }
        }
    }

    private void reopenAndLoad(String previousAuthFingerprint, boolean injectFailures) {
        DatabaseManager.reopen();
        if (!DatabaseManager.integrityCheck()) {
            throw new IllegalStateException("database integrity check failed after restore");
        }
        checkpointIf(injectFailures, Checkpoint.DATABASE_REOPENED);
        ConfigUtil.load();
        checkpointIf(injectFailures, Checkpoint.CONFIG_LOADED);
        AuthService.reload(AuthService.credentialFingerprint().equals(previousAuthFingerprint));
        checkpointIf(injectFailures, Checkpoint.AUTH_LOADED);
        AniUtil.load(false);
        checkpointIf(injectFailures, Checkpoint.SUBSCRIPTIONS_LOADED);
    }

    private void ensureTasksStoppedForRollback() {
        if (!taskService.stop(MAINTENANCE_TIMEOUT)) {
            throw new IllegalStateException("background tasks are still running; rollback is unsafe");
        }
    }

    private void stopTasksBestEffort() {
        try {
            taskService.stop(MAINTENANCE_TIMEOUT);
        } catch (RuntimeException stopFailure) {
            log.error("could not stop background tasks after restore failure type:{}",
                    stopFailure.getClass().getSimpleName());
        }
    }

    private void checkpoint(Checkpoint checkpoint) {
        failureInjector.check(checkpoint);
    }

    private void checkpointIf(boolean enabled, Checkpoint checkpoint) {
        if (enabled) {
            checkpoint(checkpoint);
        }
    }

    private void prepareDirectories(Context context) throws IOException {
        Path root = configRoot();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("configuration root cannot be a symbolic link");
        }
        Path restore = root.resolve("restore");
        Path staging = restore.resolve("staging");
        Path rollback = restore.resolve("rollback");
        Path operationsDir = restore.resolve("operations");
        for (Path path : List.of(restore, staging, rollback, operationsDir)) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new IOException("restore path cannot be a symbolic link");
            }
            Files.createDirectories(path);
            PathPolicy.realPathWithin(root, path);
        }
        Files.createDirectories(context.operationRoot);
        Files.createDirectories(context.extractedPath);
        PathPolicy.realPathWithin(root, context.operationRoot);
    }

    private static void validateRuntimeCandidates(Path extractedRoot) throws IOException {
        try {
            Config config = GsonStatic.fromJson(
                    Files.readString(extractedRoot.resolve("config.v2.json")), Config.class);
            if (config == null) {
                throw new IllegalArgumentException("configuration document is empty");
            }
            ConfigUtil.validateImportCandidate(config);

            List<Ani> subscriptions = GsonStatic.fromJsonList(
                    Files.readString(extractedRoot.resolve("ani.v2.json")), Ani.class);
            AniUtil.validateCandidate(subscriptions == null ? List.of() : subscriptions);
            AuthService.validateStateFile(extractedRoot.resolve("auth-state.v2.json"));
        } catch (RuntimeException e) {
            throw new IOException("backup runtime validation failed", e);
        }
    }

    private static List<String> compatibilityWarnings(BackupValidation validation) {
        List<String> warnings = new ArrayList<>(validation.warnings());
        if (!validation.topLevelNames().contains("database.db")) {
            warnings.add("backup has no database; local ownership and cache state will be initialized empty");
        }
        if (!validation.topLevelNames().contains("auth-state.v2.json")) {
            warnings.add("backup has no private authentication state; the upstream MD5 login remains usable");
        }
        if (!validation.topLevelNames().contains("torrents")) {
            warnings.add("backup has no torrent cache; the existing torrent cache will be reset");
        }
        return List.copyOf(warnings);
    }

    private void copyUpload(InputStream input, Path target) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".part");
        long total = 0;
        try (InputStream source = input;
             FileChannel channel = FileChannel.open(temporary,
                     StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = source.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > BackupArchive.MAX_COMPRESSED_BYTES) {
                    throw new IOException("compressed archive exceeds 50 MiB");
                }
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, read);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
            channel.force(true);
            moveExact(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void persist(Context context) {
        try {
            Path operationFile = configRoot().resolve("restore").resolve("operations")
                    .resolve(context.operationId + ".json");
            context.updatedAt = System.currentTimeMillis();
            AtomicFileWriter.writeUtf8(operationFile, GsonStatic.toJson(context.view()));
        } catch (Exception e) {
            throw new IllegalStateException("persist restore operation failed", e);
        }
    }

    private void transition(Context context, RestoreStatus status) {
        synchronized (context) {
            RestoreStatus previous = context.status;
            context.status = status;
            try {
                persist(context);
            } catch (RuntimeException e) {
                context.status = previous;
                throw e;
            }
        }
    }

    private void transitionBestEffort(Context context, RestoreStatus status) {
        synchronized (context) {
            context.status = status;
            persistBestEffort(context);
        }
    }

    private void persistBestEffort(Context context) {
        try {
            persist(context);
        } catch (RuntimeException e) {
            log.error("could not persist restore operation {} type:{}",
                    context.operationId, e.getClass().getSimpleName());
        }
    }

    private Context require(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId is required");
        }
        Context context = operations.get(operationId);
        if (context == null) {
            throw new IllegalArgumentException("restore operation not found");
        }
        return context;
    }

    private Path configRoot() {
        return ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
    }

    private Path rollbackRoot(Context context) {
        return configRoot().resolve("restore").resolve("rollback").resolve(context.operationId);
    }

    private static Path child(Path root, String name) {
        if (!SWITCH_NAMES.contains(name)) {
            throw new IllegalArgumentException("unsupported restore path");
        }
        return root.resolve(name).normalize();
    }

    private static String topLevel(String name) {
        return name;
    }

    private static void moveExact(Path source, Path target) throws IOException {
        if (Files.isSymbolicLink(source) || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("restore path conflict or symbolic link");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static String message(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    enum Checkpoint {
        TASKS_STOPPED,
        DATABASE_CLOSED,
        CURRENT_MOVED,
        STAGED_INSTALLED,
        DATABASE_REOPENED,
        CONFIG_LOADED,
        AUTH_LOADED,
        SUBSCRIPTIONS_LOADED,
        BEFORE_TASK_RESTART,
        AFTER_TASK_RESTART
    }

    @FunctionalInterface
    interface FailureInjector {
        void check(Checkpoint checkpoint);
    }

    public enum RestoreStatus {
        VALIDATED,
        INVALID,
        QUEUED,
        STOPPING,
        SWITCHING,
        SUCCEEDED,
        ROLLED_BACK,
        FAILED,
        MAINTENANCE_REQUIRED
    }

    public record RestoreOperationView(
            String operationId,
            RestoreStatus status,
            boolean legacy,
            String applicationVersion,
            List<BackupManifest.Entry> files,
            List<String> warnings,
            List<String> errors,
            List<String> rollbackFiles,
            List<String> installedFiles,
            List<String> copiedSnapshots,
            List<String> absentBefore,
            long createdAt,
            long updatedAt) {
        public RestoreOperationView {
            files = files == null ? List.of() : List.copyOf(files);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            errors = errors == null ? List.of() : List.copyOf(errors);
            rollbackFiles = rollbackFiles == null ? List.of() : List.copyOf(rollbackFiles);
            installedFiles = installedFiles == null ? List.of() : List.copyOf(installedFiles);
            copiedSnapshots = copiedSnapshots == null ? List.of() : List.copyOf(copiedSnapshots);
            absentBefore = absentBefore == null ? List.of() : List.copyOf(absentBefore);
        }
    }

    private final class Context {
        private final String operationId;
        private final long createdAt = System.currentTimeMillis();
        private final Path operationRoot;
        private final Path uploadPath;
        private final Path extractedPath;
        private volatile long updatedAt = createdAt;
        private volatile RestoreStatus status = RestoreStatus.INVALID;
        private volatile BackupValidation validation;
        private volatile List<BackupManifest.Entry> files = List.of();
        private volatile List<String> warnings = List.of();
        private volatile List<String> errors = List.of();
        private final List<String> currentMoved = new CopyOnWriteArrayList<>();
        private final List<String> installed = new CopyOnWriteArrayList<>();
        private final List<String> copiedSnapshots = new CopyOnWriteArrayList<>();
        private final List<String> absentBefore = new CopyOnWriteArrayList<>();

        private Context(String operationId) {
            this.operationId = operationId;
            Path stagingRoot = configRoot().resolve("restore").resolve("staging");
            this.operationRoot = stagingRoot.resolve(operationId);
            this.uploadPath = operationRoot.resolve("upload.zip");
            this.extractedPath = operationRoot.resolve("extracted");
        }

        private RestoreOperationView view() {
            BackupValidation current = validation;
            return new RestoreOperationView(
                    operationId,
                    status,
                    current != null && current.legacy(),
                    current == null ? null : current.applicationVersion(),
                    files,
                    warnings,
                    errors,
                    currentMoved,
                    installed,
                    copiedSnapshots,
                    absentBefore,
                    createdAt,
                    updatedAt);
        }
    }
}
