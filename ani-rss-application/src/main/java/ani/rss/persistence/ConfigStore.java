package ani.rss.persistence;

import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.JsonCompatibility;
import ani.rss.entity.Config;
import ani.rss.entity.NotificationConfig;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Pattern;

/**
 * Owns the durable configuration boundary. Callers never receive the mutable
 * runtime object and a failed write leaves both disk and memory unchanged.
 */
public final class ConfigStore {
    private static final Set<String> DOWNLOADERS = Set.of("qBittorrent", "Transmission", "Aria2", "OpenList", "Alist");
    private static final Pattern LEGACY_NOTIFICATION_ID = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final Config runtime;
    private final Supplier<Path> pathSupplier;
    private final Consumer<Config> normalizer;
    private final FileWriter writer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private Config lastCommitted;
    private JsonObject preservedDocument = new JsonObject();
    private Path preservedPath;

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

    /** Validates and normalizes a candidate without changing disk or runtime state. */
    public Config validateCandidate(Config candidate) {
        Config next = copy(candidate);
        prepare(next);
        return copy(next);
    }

    /** Validates an older partial document using the same overlay rules as load(). */
    public Config validateOverlay(Config candidate, Config defaults) {
        Config next = copy(defaults);
        BeanUtil.copyProperties(copy(candidate), next,
                CopyOptions.create().setIgnoreNullValue(true));
        prepare(next);
        return copy(next);
    }

    /** Loads a file over the supplied defaults without exposing a mutable parse result. */
    public void load(Config defaults) {
        lock.writeLock().lock();
        try {
            Config candidate = copy(defaults);
            Path path = path();
            try {
                JsonObject document = null;
                boolean retiredFieldsRemoved = false;
                if (Files.exists(path)) {
                    document = readDocument(path);
                    retiredFieldsRemoved = removeRetiredLocalFields(document);
                    Config loaded = GsonStatic.GSON.fromJson(document, Config.class);
                    if (loaded == null) {
                        throw new IllegalArgumentException("configuration document is empty");
                    }
                    BeanUtil.copyProperties(loaded, candidate,
                            CopyOptions.create().setIgnoreNullValue(true));
                }
                prepare(candidate);
                if (!Files.exists(path)) {
                    document = documentFor(path, candidate);
                    write(path, document);
                } else if (retiredFieldsRemoved) {
                    write(path, document);
                }
                replaceRuntime(candidate);
                lastCommitted = copy(candidate);
                preservedDocument = document == null ? new JsonObject() : document.deepCopy();
                preservedPath = path;
            } catch (Exception e) {
                throw failure("load configuration", e);
            }
        } finally {
            lock.writeLock().unlock();
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
            JsonObject document = documentFor(path, next);
            try {
                write(path, document);
                replaceRuntime(next);
                lastCommitted = copy(next);
                preservedDocument = document;
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
                Path path = path();
                JsonObject document = documentFor(path, candidate);
                write(path, document);
                replaceRuntime(candidate);
                lastCommitted = copy(candidate);
                preservedDocument = document;
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
            preservedDocument = documentFor(path(), value);
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
        for (NotificationConfig notification : value.getNotificationConfigList()) {
            if (notification == null) {
                throw new IllegalArgumentException("notification configuration must not contain null entries");
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

    private JsonObject documentFor(Path path, Config value) {
        ensurePreservedDocument(path);
        JsonObject current = GsonStatic.GSON.toJsonTree(value).getAsJsonObject();
        return JsonCompatibility.mergeObject(preservedDocument, current, Config.class);
    }

    private void ensurePreservedDocument(Path path) {
        if (path.equals(preservedPath)) {
            return;
        }
        try {
            preservedDocument = Files.exists(path) ? readDocument(path) : new JsonObject();
            preservedPath = path;
        } catch (IOException e) {
            throw failure("read configuration", e);
        }
    }

    private static JsonObject readDocument(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("configuration document is not an object");
        }
        return parsed.getAsJsonObject().deepCopy();
    }

    /** Removes only values emitted by earlier local builds, not future fields. */
    private static boolean removeRetiredLocalFields(JsonObject document) {
        boolean legacyLocalDocument = document.remove("imagePrivateAllowlist") != null;
        boolean changed = legacyLocalDocument;
        if (!legacyLocalDocument) {
            return false;
        }
        JsonElement notifications = document.get("notificationConfigList");
        if (notifications == null || !notifications.isJsonArray()) {
            return changed;
        }
        for (JsonElement notification : notifications.getAsJsonArray()) {
            if (!notification.isJsonObject()) {
                continue;
            }
            JsonObject value = notification.getAsJsonObject();
            JsonElement id = value.get("id");
            if (id != null && id.isJsonPrimitive() &&
                    LEGACY_NOTIFICATION_ID.matcher(id.getAsString()).matches()) {
                value.remove("id");
                changed = true;
            }
        }
        return changed;
    }

    private void write(Path path, JsonObject value) throws IOException {
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
