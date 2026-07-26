package ani.rss.persistence;

import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns the durable configuration boundary. Callers never receive the mutable
 * runtime object and a failed write leaves both disk and memory unchanged.
 */
public final class ConfigStore {
    private static final Set<String> DOWNLOADERS = Set.of("qBittorrent", "Transmission", "Aria2", "OpenList", "Alist");

    private final Config runtime;
    private final Supplier<Path> pathSupplier;
    private final Consumer<Config> normalizer;
    private final FileWriter writer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Config lastCommitted;

    public ConfigStore(Config runtime, Supplier<Path> pathSupplier, Consumer<Config> normalizer) {
        this(runtime, pathSupplier, normalizer, AtomicFileWriter::writeUtf8);
    }

    public ConfigStore(
            Config runtime,
            Supplier<Path> pathSupplier,
            Consumer<Config> normalizer,
            FileWriter writer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.pathSupplier = Objects.requireNonNull(pathSupplier, "pathSupplier");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.lastCommitted = copy(runtime);
    }

    public Config snapshot() {
        lock.readLock().lock();
        try {
            return copy(runtime);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Config committedSnapshot() {
        lock.readLock().lock();
        try {
            return copy(lastCommitted);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Loads a file over the supplied defaults without exposing a mutable parse result. */
    public void load(Config defaults) {
        Config candidate = copy(defaults);
        Path path = path();
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path);
                Config loaded = GsonStatic.GSON.fromJson(json, Config.class);
                if (loaded == null) {
                    throw new IllegalArgumentException("configuration document is empty");
                }
                BeanUtil.copyProperties(loaded, candidate,
                        CopyOptions.create().setIgnoreNullValue(true));
            }
            prepare(candidate);
            if (!Files.exists(path)) {
                write(path, candidate);
            }
            replaceRuntime(candidate);
            lastCommitted = copy(candidate);
        } catch (Exception e) {
            throw failure("load configuration", e);
        }
    }

    /** Persists and activates a complete candidate. */
    public void commit(Config candidate) {
        lock.writeLock().lock();
        try {
            Config next = copy(candidate);
            prepare(next);
            Config previous = copy(lastCommitted);
            Path path = path();
            try {
                write(path, next);
                replaceRuntime(next);
                lastCommitted = copy(next);
            } catch (Exception e) {
                replaceRuntime(previous);
                throw e;
            }
        } catch (Exception e) {
            throw failure("save configuration", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Persists a legacy caller's in-place mutation transactionally. */
    public void commitRuntimeCandidate() {
        lock.writeLock().lock();
        try {
            Config candidate = copy(runtime);
            Config previous = copy(lastCommitted);
            try {
                prepare(candidate);
                write(path(), candidate);
                replaceRuntime(candidate);
                lastCommitted = copy(candidate);
            } catch (Exception e) {
                replaceRuntime(previous);
                throw e;
            }
        } catch (Exception e) {
            throw failure("save configuration", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Marks an externally loaded runtime snapshot as the rollback baseline. */
    public void markCommitted(Config value) {
        lock.writeLock().lock();
        try {
            lastCommitted = copy(value);
            replaceRuntime(value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Path path() {
        return pathSupplier.get().toAbsolutePath().normalize();
    }

    private void prepare(Config value) {
        if (value == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        normalizer.accept(value);
        validate(value);
    }

    private static void validate(Config value) {
        if (value.getLogin() == null) {
            throw new IllegalArgumentException("login configuration is required");
        }
        if (value.getNotificationTemplate() == null ||
                value.getNotificationConfigList() == null) {
            throw new IllegalArgumentException("notification configuration is incomplete");
        }
        Set<String> notificationIds = new HashSet<>();
        for (NotificationConfig notification : value.getNotificationConfigList()) {
            if (notification == null || notification.getId() == null || notification.getId().isBlank() ||
                    !notificationIds.add(notification.getId())) {
                throw new IllegalArgumentException("notification identifiers must be unique");
            }
        }
        if (value.getDownloadToolType() == null ||
                !DOWNLOADERS.contains(value.getDownloadToolType())) {
            throw new IllegalArgumentException("unsupported download tool");
        }
        if (value.getDownloadRetry() == null || value.getDownloadRetry() < 1 ||
                value.getDownloadRetry() > 10) {
            throw new IllegalArgumentException("downloadRetry must be between 1 and 10");
        }
        if (value.getRssSleepMinutes() == null || value.getRssSleepMinutes() < 1 ||
                value.getRenameSleepSeconds() == null || value.getRenameSleepSeconds() < 0) {
            throw new IllegalArgumentException("task interval is invalid");
        }
        if (value.getLoginEffectiveHours() == null || value.getLoginEffectiveHours() < 1 ||
                value.getLoginEffectiveHours() > 8760) {
            throw new IllegalArgumentException("loginEffectiveHours must be between 1 and 8760");
        }
        if (Boolean.TRUE.equals(value.getProxy()) &&
                (value.getProxyHost() == null || value.getProxyHost().isBlank() ||
                        value.getProxyPort() == null || value.getProxyPort() < 1 || value.getProxyPort() > 65535)) {
            throw new IllegalArgumentException("proxy configuration is invalid");
        }
        if (value.getOpenListDownloadTimeout() == null || value.getOpenListDownloadTimeout() < 1 ||
                value.getOpenListDownloadTimeout() > 3600 ||
                value.getOpenListDownloadRetryNumber() == null ||
                value.getOpenListDownloadRetryNumber() < 0 || value.getOpenListDownloadRetryNumber() > 20) {
            throw new IllegalArgumentException("OpenList retry settings are invalid");
        }
        if (value.getConfigBackupDay() == null || value.getConfigBackupDay() < 1 ||
                value.getConfigBackupDay() > 3650) {
            throw new IllegalArgumentException("configBackupDay is invalid");
        }
    }

    private void replaceRuntime(Config value) {
        BeanUtil.copyProperties(copy(value), runtime,
                CopyOptions.create().setIgnoreNullValue(false));
    }

    private void write(Path path, Config value) throws IOException {
        writer.write(path, GsonStatic.toJson(value));
    }

    private static Config copy(Config value) {
        return GsonStatic.GSON.fromJson(GsonStatic.toJson(value), Config.class);
    }

    private static IllegalStateException failure(String action, Exception e) {
        if (e instanceof IllegalStateException stateException &&
                stateException.getCause() != null) {
            return stateException;
        }
        return new IllegalStateException(action + " failed", e);
    }

    @FunctionalInterface
    public interface FileWriter {
        void write(Path path, String content) throws IOException;
    }
}
