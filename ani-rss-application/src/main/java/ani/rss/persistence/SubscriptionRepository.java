package ani.rss.persistence;

import ani.rss.commons.AtomicFileWriter;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.JsonCompatibility;
import ani.rss.entity.Ani;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Transactional persistence boundary for subscription records. */
public final class SubscriptionRepository {
    private static final Type LIST_TYPE = new TypeToken<List<Ani>>() { }.getType();

    private final List<Ani> runtime;
    private final Supplier<Path> pathSupplier;
    private final FileWriter writer;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private List<Ani> lastCommitted = List.of();
    private JsonArray preservedDocument = new JsonArray();
    private Path preservedPath;

    public SubscriptionRepository(List<Ani> runtime, Supplier<Path> pathSupplier) {
        this(runtime, pathSupplier, AtomicFileWriter::writeUtf8);
    }

    public SubscriptionRepository(List<Ani> runtime, Supplier<Path> pathSupplier, FileWriter writer) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.pathSupplier = Objects.requireNonNull(pathSupplier, "pathSupplier");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.lastCommitted = copyList(runtime);
    }

    public List<Ani> snapshot() {
        lock.readLock().lock();
        try {
            return copyList(runtime);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Ani> committedSnapshot() {
        lock.readLock().lock();
        try {
            return copyList(lastCommitted);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the canonical runtime record for internal background work. */
    public Optional<Ani> findRuntimeById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        lock.readLock().lock();
        try {
            return runtime.stream()
                    .filter(Objects::nonNull)
                    .filter(ani -> id.equals(ani.getId()))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Validates a candidate without changing disk or runtime state. */
    public List<Ani> validateCandidate(List<Ani> candidate) {
        return validate(candidate);
    }

    public List<Ani> readFromDisk() throws IOException {
        lock.writeLock().lock();
        try {
            Path path = path();
            if (!Files.exists(path)) {
                return copyList(runtime);
            }
            preservedDocument = readDocument(path);
            preservedPath = path;
            List<Ani> value = GsonStatic.GSON.fromJson(preservedDocument, LIST_TYPE);
            return validate(value == null ? List.of() : value);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void commit(List<Ani> candidate) {
        lock.writeLock().lock();
        try {
            List<Ani> next = validate(candidate);
            List<Ani> previous = copyList(lastCommitted);
            Path path = path();
            JsonArray document = documentFor(path, next);
            try {
                write(path, document);
                replaceRuntime(next);
                lastCommitted = copyList(next);
                preservedDocument = document;
            } catch (Exception e) {
                replaceRuntime(previous);
                throw e;
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("save subscriptions failed", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Persists mutations made through the legacy public list. */
    public void commitRuntimeCandidate() {
        lock.writeLock().lock();
        try {
            List<Ani> next = validate(runtime);
            List<Ani> previous = copyList(lastCommitted);
            Path path = path();
            JsonArray document = documentFor(path, next);
            try {
                write(path, document);
                lastCommitted = copyList(next);
                preservedDocument = document;
            } catch (Exception e) {
                replaceRuntime(previous);
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("save subscriptions failed", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void markCommitted(List<Ani> value) {
        lock.writeLock().lock();
        try {
            List<Ani> next = validate(value);
            replaceRuntime(next);
            lastCommitted = copyList(next);
            preservedDocument = documentFor(path(), next);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void replaceFromDisk(List<Ani> value) {
        markCommitted(value);
    }

    public Path path() {
        return pathSupplier.get().toAbsolutePath().normalize();
    }

    private List<Ani> validate(List<Ani> value) {
        if (value == null) {
            throw new IllegalArgumentException("subscriptions must not be null");
        }
        List<Ani> next = copyList(value);
        Set<String> ids = new HashSet<>();
        for (Ani ani : next) {
            if (ani == null) {
                throw new IllegalArgumentException("subscription must not be null");
            }
            if (ani.getId() != null && !ani.getId().isBlank() && !ids.add(ani.getId())) {
                throw new IllegalArgumentException("duplicate subscription id");
            }
        }
        return next;
    }

    private void replaceRuntime(List<Ani> value) {
        runtime.clear();
        runtime.addAll(copyList(value));
    }

    private JsonArray documentFor(Path path, List<Ani> value) {
        ensurePreservedDocument(path);
        JsonArray current = GsonStatic.GSON.toJsonTree(value, LIST_TYPE).getAsJsonArray();
        return JsonCompatibility.mergeArray(preservedDocument, current, Ani.class);
    }

    private void ensurePreservedDocument(Path path) {
        if (path.equals(preservedPath)) {
            return;
        }
        try {
            preservedDocument = Files.exists(path) ? readDocument(path) : new JsonArray();
            preservedPath = path;
        } catch (IOException e) {
            throw new IllegalStateException("read subscriptions failed", e);
        }
    }

    private static JsonArray readDocument(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path));
        if (!parsed.isJsonArray()) {
            throw new IllegalArgumentException("subscription document is not an array");
        }
        return parsed.getAsJsonArray().deepCopy();
    }

    private void write(Path path, JsonArray value) throws IOException {
        writer.write(path, GsonStatic.toJson(value));
    }

    private static List<Ani> copyList(List<Ani> value) {
        if (value == null) {
            return new ArrayList<>();
        }
        List<Ani> result = new ArrayList<>(value.size());
        for (Ani ani : value) {
            result.add(ani == null ? null : GsonStatic.GSON.fromJson(GsonStatic.toJson(ani), Ani.class));
        }
        return result;
    }

    @FunctionalInterface
    public interface FileWriter {
        void write(Path path, String content) throws IOException;
    }
}
