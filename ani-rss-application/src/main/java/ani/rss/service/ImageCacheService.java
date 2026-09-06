package ani.rss.service;

import ani.rss.auth.AuthService;
import ani.rss.auth.AuthenticationFailureException;
import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.crypto.SecureUtil;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ImageCacheService {
    private static final long TTL = Duration.ofHours(2).toMillis();
    private static final long PUBLIC_TTL = Duration.ofHours(2).toMillis();
    private static final int MAX_URL_LENGTH = 8192;
    private static final int LOCK_STRIPES = 256;
    private static final int PUBLIC_MAX_ENTRIES = 5_000;
    private static final long PUBLIC_MAX_BYTES = 256L * 1024 * 1024;
    private static final long PUBLIC_FAILURE_TTL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final int PUBLIC_QUEUE_CAPACITY = 48;
    private static final int PUBLIC_WORKERS = 6;
    private static final long PUBLIC_WAIT_TIMEOUT_MILLIS = 12_000;
    private static final String PUBLIC_MANIFEST = "public-manifest.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> sourceIndex = new ConcurrentHashMap<>();
    private final Object[] sourceLocks = createLocks();
    private final Map<String, PublicEntry> publicEntries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PublicEntry>> publicFlights = new ConcurrentHashMap<>();
    private final Map<String, FailureEntry> publicFailures = new ConcurrentHashMap<>();
    private final Object publicManifestLock = new Object();
    private final ExecutorService publicExecutor = new ThreadPoolExecutor(
            PUBLIC_WORKERS,
            PUBLIC_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PUBLIC_QUEUE_CAPACITY),
            daemonFactory("ani-rss-image"),
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final ScheduledExecutorService maintenanceExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    daemonFactory("ani-rss-image-maintenance"));
    private volatile boolean publicManifestLoaded;

    @PostConstruct
    void loadPublicManifestOnStartup() {
        loadPublicManifest();
        maintenanceExecutor.scheduleWithFixedDelay(this::maintainPublicCache,
                10, 10, TimeUnit.MINUTES);
    }

    @PreDestroy
    void closeImageClients() {
        publicExecutor.shutdownNow();
        maintenanceExecutor.shutdownNow();
        SafeImageFetcher.closeCachedClients();
    }

    /**
     * Resolves a public cover through the shared content cache. The caller
     * still has to be authenticated, but the cache key deliberately does not
     * contain a session binding so two sessions can reuse the same cover.
     */
    public PublicImage publicImage(String url, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        String canonical = canonicalPublicUrl(url);
        loadPublicManifest();
        String key = SecureUtil.sha256(canonical);
        FailureEntry failure = publicFailures.get(key);
        if (failure != null) {
            long remaining = failure.retryAt() - System.currentTimeMillis();
            if (remaining > 0) {
                throw new UpstreamServiceException("image source is temporarily unavailable; retry later",
                        failure.cause(), Math.max(1, (remaining + 999) / 1000));
            }
            publicFailures.remove(key, failure);
        }
        PublicEntry cached = publicEntries.get(key);
        if (isUsable(cached)) {
            return new PublicImage(cached.path(), cached.contentType(), cached.etag(),
                    cached.expiresAt(), cached.length());
        }
        if (cached != null) {
            removePublicEntry(cached);
        }

        CompletableFuture<PublicEntry> created = new CompletableFuture<>();
        CompletableFuture<PublicEntry> shared = publicFlights.putIfAbsent(key, created);
        if (shared == null) {
            try {
                publicExecutor.execute(() -> loadPublicImage(canonical, key, created));
                shared = created;
            } catch (RejectedExecutionException e) {
                publicFlights.remove(key, created);
                created.completeExceptionally(new UpstreamServiceException(
                        "image cache is busy; retry later", e, 5));
                shared = created;
            }
        }
        try {
            PublicEntry image = shared.get(PUBLIC_WAIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return new PublicImage(image.path(), image.contentType(), image.etag(),
                    image.expiresAt(), image.length());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamServiceException("image fetch interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new UpstreamServiceException("image fetch failed", cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new UpstreamServiceException("image fetch timed out", e);
        }
    }

    private void loadPublicImage(String canonical, String key, CompletableFuture<PublicEntry> future) {
        try {
            SafeImageFetcher.FetchedImage fetched = SafeImageFetcher.fetch(canonical, ConfigUtil.snapshot());
            Path root = publicRoot();
            Files.createDirectories(root);
            verifyCacheRoot(root);
            String extension = extension(fetched.contentType());
            Path target = root.resolve(key + extension).normalize();
            Path temporary = Files.createTempFile(root, ".public-image-", ".part");
            try {
                fetched.writeTo(temporary);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            long now = System.currentTimeMillis();
            byte[] bytes = fetched.bytes();
            PublicEntry entry = new PublicEntry(
                    key,
                    canonical,
                    target,
                    fetched.contentType(),
                    etag(bytes),
                    now,
                    now + PUBLIC_TTL,
                    bytes.length);
            publicEntries.put(key, entry);
            publicFailures.remove(key);
            trimPublicEntries();
            persistPublicManifest();
            future.complete(entry);
        } catch (Exception e) {
            RuntimeException failure = e instanceof UpstreamServiceException upstream
                    && upstream.retryAfterSeconds() > 0
                    ? upstream
                    : new UpstreamServiceException(
                    "image fetch failed", e, PUBLIC_FAILURE_TTL_MILLIS / 1000);
            publicFailures.put(key, new FailureEntry(
                    System.currentTimeMillis() + PUBLIC_FAILURE_TTL_MILLIS, failure));
            future.completeExceptionally(failure);
        } finally {
            publicFlights.remove(key, future);
        }
    }

    private boolean isUsable(PublicEntry entry) {
        return entry != null
                && entry.expiresAt() > System.currentTimeMillis()
                && Files.isRegularFile(entry.path(), LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(entry.path());
    }

    private static String canonicalPublicUrl(String url) {
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("image URL is invalid or too long");
        }
        try {
            URI parsed = new URI(url.trim());
            String scheme = parsed.getScheme() == null
                    ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null
                    ? "" : parsed.getHost().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || host.isBlank()
                    || parsed.getUserInfo() != null
                    || parsed.getFragment() != null
                    || hasPrivateQuery(parsed.getRawQuery())) {
                throw new IllegalArgumentException("image URL is not public");
            }
            int port = parsed.getPort();
            if (("http".equals(scheme) && port == 80)
                    || ("https".equals(scheme) && port == 443)) {
                port = -1;
            }
            String authority = host.contains(":") && !host.startsWith("[")
                    ? "[" + host + "]" : host;
            String portSuffix = port < 0 ? "" : ":" + port;
            String path = parsed.getRawPath() == null ? "" : parsed.getRawPath();
            String query = parsed.getRawQuery();
            return new URI(scheme + "://" + authority + portSuffix + path
                    + (query == null ? "" : "?" + query)).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("invalid image URL", e);
        }
    }

    private static boolean hasPrivateQuery(String query) {
        if (query == null || query.isBlank()) return false;
        for (String part : query.split("&")) {
            String name = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            if (name.contains("token") || name.contains("signature") || name.equals("sig")
                    || name.contains("credential") || name.contains("secret")
                    || name.equals("auth") || name.equals("expires")) {
                return true;
            }
        }
        return false;
    }

    private Path publicRoot() {
        return ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize()
                .resolve("image-cache").resolve("public").normalize();
    }

    private static void verifyCacheRoot(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            throw new IOException("image cache directory is a symbolic link");
        }
        Path parent = root.getParent();
        if (parent == null) throw new IOException("image cache directory must have a parent");
        PathPolicy.requireNoSymbolicLinks(parent, root);
        PathPolicy.realPathWithin(parent, root);
    }

    private void loadPublicManifest() {
        if (publicManifestLoaded) return;
        synchronized (publicManifestLock) {
            if (publicManifestLoaded) return;
            Path manifest = publicRoot().resolve(PUBLIC_MANIFEST);
            try {
                if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    PublicManifest value = GsonStatic.fromJson(Files.readString(manifest), PublicManifest.class);
                    for (PublicManifestEntry item : value == null || value.entries() == null
                            ? List.<PublicManifestEntry>of() : value.entries()) {
                        if (item == null || item.key() == null || item.url() == null) continue;
                        Path path = publicRoot().resolve(item.key() + extension(item.contentType())).normalize();
                        if (!path.startsWith(publicRoot()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(path)) continue;
                        publicEntries.put(item.key(), new PublicEntry(
                                item.key(), item.url(), path, item.contentType(), item.etag(),
                                item.fetchedAt(), item.expiresAt(), item.length()));
                    }
                    trimPublicEntries();
                }
            } catch (Exception ignored) {
                // A corrupt optional image manifest must never prevent startup.
                publicEntries.clear();
            } finally {
                publicManifestLoaded = true;
            }
        }
    }

    private void persistPublicManifest() {
        synchronized (publicManifestLock) {
            try {
                Path root = publicRoot();
                Files.createDirectories(root);
                verifyCacheRoot(root);
                List<PublicManifestEntry> entries = publicEntries.values().stream()
                        .map(item -> new PublicManifestEntry(item.key(), item.url(), item.contentType(),
                                item.etag(), item.fetchedAt(), item.expiresAt(), item.length()))
                        .sorted(Comparator.comparing(PublicManifestEntry::fetchedAt))
                        .toList();
                Path temporary = Files.createTempFile(root, ".public-manifest-", ".part");
                try {
                    Files.writeString(temporary, GsonStatic.toJson(new PublicManifest(entries)));
                    try {
                        Files.move(temporary, root.resolve(PUBLIC_MANIFEST),
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temporary, root.resolve(PUBLIC_MANIFEST),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException | RuntimeException ignored) {
                // Public caching is an acceleration layer, not a reason to fail a request.
            }
        }
    }

    private void trimPublicEntries() {
        long now = System.currentTimeMillis();
        for (PublicEntry entry : List.copyOf(publicEntries.values())) {
            if (entry.expiresAt() > now) continue;
            removePublicEntry(entry);
        }
        long total = publicEntries.values().stream().mapToLong(PublicEntry::length).sum();
        if (publicEntries.size() <= PUBLIC_MAX_ENTRIES && total <= PUBLIC_MAX_BYTES) return;
        List<PublicEntry> oldest = publicEntries.values().stream()
                .sorted(Comparator.comparingLong(PublicEntry::fetchedAt))
                .toList();
        for (PublicEntry entry : oldest) {
            if (publicEntries.size() <= PUBLIC_MAX_ENTRIES && total <= PUBLIC_MAX_BYTES) break;
            if (removePublicEntry(entry)) total -= entry.length();
        }
    }

    private boolean removePublicEntry(PublicEntry entry) {
        try {
            Files.deleteIfExists(entry.path());
            return publicEntries.remove(entry.key(), entry);
        } catch (IOException e) {
            // Keep the manifest entry until the next maintenance pass so a
            // transient filesystem failure cannot orphan a tracked file.
            org.slf4j.LoggerFactory.getLogger(ImageCacheService.class)
                    .warn("public image cleanup deferred for {}: {}", entry.path(), e.getMessage());
            return false;
        }
    }

    private void maintainPublicCache() {
        try {
            loadPublicManifest();
            trimPublicEntries();
            persistPublicManifest();
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ImageCacheService.class)
                    .warn("public image maintenance failed: {}", e.getMessage());
        }
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)) + "\"";
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash image", e);
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }

    public ImageRef cache(String url, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        if (url == null || url.isBlank() || url.length() > MAX_URL_LENGTH) {
            throw new IllegalArgumentException("image URL is invalid or too long");
        }
        String binding = AuthService.sessionBinding(request);
        String sourceKey = binding + ":" + SecureUtil.sha256(url);
        ImageRef existing = existing(sourceKey);
        if (existing != null) {
            return existing;
        }
        Object lock = sourceLocks[Math.floorMod(sourceKey.hashCode(), sourceLocks.length)];
        try {
            synchronized (lock) {
                existing = existing(sourceKey);
                if (existing != null) {
                    return existing;
                }
                SafeImageFetcher.FetchedImage fetched;
                try {
                    fetched = SafeImageFetcher.fetch(url, ConfigUtil.snapshot());
                } catch (IllegalArgumentException e) {
                    throw e;
                } catch (IllegalStateException e) {
                    throw new UpstreamServiceException("image fetch failed", e);
                }
                String id = randomId();
                String extension = extension(fetched.contentType());
                Path root = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve("image-cache");
                Path target = root.resolve(id + extension).normalize();
                Files.createDirectories(root);
                if (Files.isSymbolicLink(root)) {
                    throw new IOException("image cache directory is a symbolic link");
                }
                Path rootParent = root.getParent();
                if (rootParent == null) {
                    throw new IOException("image cache directory must have a parent");
                }
                PathPolicy.requireNoSymbolicLinks(rootParent, root);
                PathPolicy.realPathWithin(rootParent, root);
                Path temporary = Files.createTempFile(root, ".image-", ".part");
                try {
                    fetched.writeTo(temporary);
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
                long expiresAt = System.currentTimeMillis() + TTL;
                entries.put(id, new Entry(target, fetched.contentType(), binding, sourceKey, expiresAt));
                sourceIndex.put(sourceKey, id);
                trimExpired();
                return new ImageRef(id, expiresAt);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cache image failed", e);
        }
    }

    private static Object[] createLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        java.util.Arrays.setAll(locks, ignored -> new Object());
        return locks;
    }

    public CachedImage resolve(String id, HttpServletRequest request) {
        if (!AuthService.validateRequest(request)) {
            throw new AuthenticationFailureException("session required");
        }
        Entry entry = entries.get(id);
        if (entry == null || entry.expiresAt < System.currentTimeMillis()) {
            removeEntry(id);
            throw new IllegalArgumentException("image cache id expired");
        }
        if (!entry.binding.equals(AuthService.sessionBinding(request))) {
            throw new IllegalArgumentException("image cache id belongs to another session");
        }
        if (!Files.isRegularFile(entry.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry.path)) {
            throw new IllegalArgumentException("cached image is unavailable");
        }
        return new CachedImage(entry.path, entry.contentType);
    }

    private void trimExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().stream()
                .filter(entry -> entry.getValue().expiresAt < now)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(this::removeEntry);
        if (entries.size() > 10_000) {
            entries.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.expiresAt, b.expiresAt)))
                    .limit(entries.size() - 10_000)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(this::removeEntry);
        }
    }

    private ImageRef existing(String sourceKey) {
        String id = sourceIndex.get(sourceKey);
        Entry entry = id == null ? null : entries.get(id);
        if (entry == null || entry.expiresAt < System.currentTimeMillis() ||
                !Files.isRegularFile(entry.path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry.path)) {
            if (id != null) {
                removeEntry(id);
            }
            return null;
        }
        return new ImageRef(id, entry.expiresAt);
    }

    private void removeEntry(String id) {
        Entry removed = entries.remove(id);
        if (removed == null) {
            return;
        }
        sourceIndex.remove(removed.sourceKey, id);
        try {
            Files.deleteIfExists(removed.path);
        } catch (IOException ignored) {
            // The exact cache file will be retried by a later maintenance pass.
        }
    }

    private static String extension(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/bmp" -> ".bmp";
            default -> ".img";
        };
    }

    private static String randomId() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record ImageRef(String id, long expiresAt) {
    }

    public record CachedImage(Path path, String contentType) {
    }

    public record PublicImage(Path path, String contentType, String etag,
                              long expiresAt, long length) {
    }

    private record Entry(Path path, String contentType, String binding, String sourceKey, long expiresAt) {
    }

    private record PublicEntry(String key, String url, Path path, String contentType,
                               String etag, long fetchedAt, long expiresAt, long length) {
    }

    private record PublicManifest(List<PublicManifestEntry> entries) {
    }

    private record PublicManifestEntry(String key, String url, String contentType,
                                       String etag, long fetchedAt, long expiresAt, long length) {
    }

    private record FailureEntry(long retryAt, Throwable cause) {
    }
}
