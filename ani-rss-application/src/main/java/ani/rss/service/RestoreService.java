package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.backup.BackupManifest;
import ani.rss.backup.BackupValidation;
import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.persistence.DatabaseManager;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class RestoreService {
    private static final Duration MAINTENANCE_TIMEOUT = Duration.ofSeconds(30);
    private static final List<String> SWITCH_NAMES = List.of(
            "config.v2.json", "ani.v2.json", "database.db", "files", "torrents");

    private final TaskService taskService;
    private final TaskCoordinator taskCoordinator;
    private final Map<String, Context> operations = new ConcurrentHashMap<>();
    private final ExecutorService executor;

    public RestoreService(TaskService taskService, TaskCoordinator taskCoordinator) {
        this.taskService = taskService;
        this.taskCoordinator = taskCoordinator;
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
            context.status = RestoreStatus.VALIDATED;
            context.warnings = context.validation.warnings();
            context.files = context.validation.files();
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
        return require(operationId).view();
    }

    private void execute(Context context) {
        boolean tasksWereRunning = TaskService.LOOP.get() &&
                TaskService.THREADS.stream().anyMatch(Thread::isAlive);
        TaskCoordinator.MaintenanceLease lease = null;
        List<String> currentMoved = new ArrayList<>();
        List<String> installed = new ArrayList<>();
        try {
            lease = taskCoordinator.enterMaintenance(MAINTENANCE_TIMEOUT);
            context.tasksWereRunning = tasksWereRunning;
            context.status = RestoreStatus.STOPPING;
            persist(context);
            if (!taskService.stop(MAINTENANCE_TIMEOUT)) {
                throw new IllegalStateException("background tasks did not stop within 30 seconds");
            }
            DatabaseManager.close();
            context.status = RestoreStatus.SWITCHING;
            persist(context);

            Path currentRoot = rollbackRoot(context).resolve("current");
            Path failedRoot = rollbackRoot(context).resolve("failed");
            Files.createDirectories(currentRoot);
            Files.createDirectories(failedRoot);

            for (String name : SWITCH_NAMES) {
                if (!context.validation.topLevelNames().contains(topLevel(name))) {
                    continue;
                }
                Path current = child(configRoot(), name);
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    moveExact(current, currentRoot.resolve(name));
                    currentMoved.add(name);
                }
                Path staged = child(context.extractedPath, name);
                if (Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
                    moveExact(staged, current);
                    installed.add(name);
                }
            }

            reopenAndLoad();
            if (tasksWereRunning) {
                taskService.start();
            }
            context.status = RestoreStatus.SUCCEEDED;
            persist(context);
            lease.complete(tasksWereRunning);
        } catch (Exception failure) {
            context.errors = List.of(message(failure));
            boolean rolledBack = false;
            if (lease != null) {
                try {
                    rollback(context, currentMoved, installed);
                    reopenAndLoad();
                    if (tasksWereRunning) {
                        taskService.start();
                    }
                    context.status = RestoreStatus.ROLLED_BACK;
                    rolledBack = true;
                    persist(context);
                    lease.complete(tasksWereRunning);
                } catch (Exception rollbackFailure) {
                    context.errors = List.of(message(failure), message(rollbackFailure));
                    context.status = RestoreStatus.MAINTENANCE_REQUIRED;
                    persist(context);
                    lease.fail();
                }
            } else {
                context.status = RestoreStatus.FAILED;
                persist(context);
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

    private void rollback(Context context, List<String> currentMoved, List<String> installed) throws IOException {
        DatabaseManager.close();
        Path currentRoot = rollbackRoot(context).resolve("current");
        Path failedRoot = rollbackRoot(context).resolve("failed");
        for (int i = installed.size() - 1; i >= 0; i--) {
            String name = installed.get(i);
            Path current = child(configRoot(), name);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(current, failedRoot.resolve(name));
            }
        }
        for (int i = currentMoved.size() - 1; i >= 0; i--) {
            String name = currentMoved.get(i);
            Path backup = child(currentRoot, name);
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                moveExact(backup, child(configRoot(), name));
            }
        }
    }

    private void reopenAndLoad() {
        DatabaseManager.reopen();
        if (!DatabaseManager.integrityCheck()) {
            throw new IllegalStateException("database integrity check failed after restore");
        }
        ConfigUtil.load();
        AniUtil.load();
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
            log.warn("could not persist restore operation {}", context.operationId);
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
            long createdAt,
            long updatedAt) {
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
        private volatile boolean tasksWereRunning;
        private volatile List<BackupManifest.Entry> files = List.of();
        private volatile List<String> warnings = List.of();
        private volatile List<String> errors = List.of();

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
                    createdAt,
                    updatedAt);
        }
    }
}
