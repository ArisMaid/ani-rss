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
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ImageCacheService {
    private static final long TTL = Duration.ofHours(2).toMillis();
    private static final long PUBLIC_TTL = Duration.ofHours(2).toMillis();
    private static final int MAX_URL_LENGTH = 8192;
    private static final int LOCK_STRIPES = 256;
    private static final int PUBLIC_MAX_ENTRIES = 5_000;
    private static final long PUBLIC_MAX_BYTES = 256L * 1024 * 1024;
    private static final long PUBLIC_FAILURE_TTL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final int PUBLIC_FAILURE_MAX_ENTRIES = 5_000;
    private static final int PUBLIC_QUEUE_CAPACITY = 48;
    private static final int PUBLIC_WORKERS = 6;
    private static final long PUBLIC_WAIT_TIMEOUT_MILLIS = 12_000;
    private static final long PUBLIC_BUSY_RETRY_SECONDS = 1;
    private static final long MANIFEST_RETRY_INITIAL_MILLIS = 1_000;
    private static final long MANIFEST_RETRY_MAX_MILLIS = 30_000;
    private static final long MANIFEST_CLOSE_TIMEOUT_MILLIS = 5_000;
    private static final int PUBLIC_PENDING_MAX_FILES = 1_024;
    private static final int PUBLIC_PENDING_SCAN_LIMIT = 32;
    private static final long PUBLIC_PENDING_RETRY_INITIAL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final long PUBLIC_PENDING_RETRY_MAX_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final long PUBLIC_MAINTENANCE_WARNING_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final String PUBLIC_MANIFEST = "public-manifest.json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> sourceIndex = new ConcurrentHashMap<>();
    private final Object[] sourceLocks = createLocks();
    private final Map<String, PublicEntry> publicEntries = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<PublicEntry>> publicFlights = new ConcurrentHashMap<>();
    private final Map<String, FailureEntry> publicFailures = new ConcurrentHashMap<>();
    private final Map<String, PendingDeletion> publicPendingDeletions = new ConcurrentHashMap<>();
    private final Object[] publicKeyLocks = createLocks();
    private final Object publicManifestLock = new Object();
    private final Object publicManifestStateLock = new Object();
    private final ReentrantLock publicManifestWriterLock = new ReentrantLock();
    private final AtomicLong publicManifestRevision = new AtomicLong();
    private final AtomicLong publicManifestWriteAttempts = new AtomicLong();
    private final AtomicLong publicManifestWriteSuccesses = new AtomicLong();
    private final AtomicLong publicManifestWriteFailures = new AtomicLong();
    private final AtomicLong publicPendingDeletionRetries = new AtomicLong();
    private final AtomicInteger publicPendingDeletionScanCursor = new AtomicInteger();
    private final AtomicLong publicCapacityRejections = new AtomicLong();
    private final AtomicBoolean publicManifestWriteScheduled = new AtomicBoolean();
    private final AtomicBoolean publicManifestWriterRunning = new AtomicBoolean();
    private final AtomicLong publicReservedBytes = new AtomicLong();
    private final AtomicInteger publicReservedFiles = new AtomicInteger();
    private final AtomicLong publicPendingDeletionBytes = new AtomicLong();
    private final AtomicInteger publicPendingDeletionFiles = new AtomicInteger();
    private final AtomicBoolean publicCacheMaintenancePaused = new AtomicBoolean();
    private final AtomicLong publicCacheMaintenanceWarningAt = new AtomicLong();
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
    private final ScheduledExecutorService publicManifestExecutor =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                    daemonFactory("ani-rss-image-manifest"));
    private volatile boolean publicManifestLoaded;
    private volatile boolean publicManifestDirty;
    private volatile long publicManifestPersistedRevision;
    private volatile long publicManifestLastWriteDurationMillis;
    private volatile String publicManifestLastFinalFlushStatus = "not-attempted";
    private volatile long publicManifestLastFinalFlushDurationMillis;
    private volatile String publicManifestLastWriteFailure = "";
    private final AtomicReference<PublicLifecycle> publicLifecycle =
            new AtomicReference<>(PublicLifecycle.OPEN);
    private volatile boolean closed;
    private final PublicManifestPersistence publicManifestPersistence;

    ImageCacheService() {
        this(ImageCacheService::writeManifestFile);
    }

    ImageCacheService(PublicManifestPersistence publicManifestPersistence) {
        this.publicManifestPersistence = publicManifestPersistence == null
                ? ImageCacheService::writeManifestFile : publicManifestPersistence;
    }

    @PostConstruct
    void loadPublicManifestOnStartup() {
        loadPublicManifest();
        maintenanceExecutor.scheduleWithFixedDelay(this::maintainPublicCache,
                30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    void closeImageClients() {
        synchronized (publicManifestStateLock) {
            if (!publicLifecycle.compareAndSet(PublicLifecycle.OPEN, PublicLifecycle.CLOSING)) return;
            closed = true;
        }
        publicExecutor.shutdownNow();
        publicFlights.forEach((key, future) -> {
            future.completeExceptionally(new UpstreamServiceException(
                    "image cache is closed", null, 0));
            publicFlights.remove(key, future);
        });
        maintenanceExecutor.shutdownNow();
        flushPublicManifestOnClose();
        publicLifecycle.set(PublicLifecycle.CLOSED);
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
        if (closed) {
            throw new UpstreamServiceException("image cache is closed", null, 0);
        }
        String canonical = canonicalPublicUrl(url);
        loadPublicManifest();
        trimPublicFailures();
        String key = SecureUtil.sha256(canonical);
        FailureEntry failure = publicFailures.get(key);
        if (failure != null) {
            long remaining = failure.retryAt() - System.currentTimeMillis();
            if (remaining > 0) {
                throw new UpstreamServiceException("image source is temporarily unavailable ("
                        + failure.reason() + "); retry later",
                        null, Math.max(1, (remaining + 999) / 1000));
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
        if (isPublicCacheAdmissionPaused()) {
            throw new UpstreamServiceException(
                    "image cache maintenance is paused; check cache permissions or disk",
                    null, PUBLIC_BUSY_RETRY_SECONDS);
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

    /**
     * Exposes bounded, low-cardinality cache diagnostics for smoke tests and
     * operational health checks. It intentionally excludes URLs and paths.
     */
    public PublicCacheDiagnostics publicCacheDiagnostics() {
        long entryBytes = publicEntries.values().stream().mapToLong(PublicEntry::length).sum();
        long trackedBytes = publicTrackedBytes();
        int trackedFiles = publicTrackedFiles();
        return new PublicCacheDiagnostics(
                publicLifecycle.get().name(),
                publicCacheMaintenancePaused.get(),
                publicManifestRevision.get(),
                publicManifestPersistedRevision,
                publicManifestWriteScheduled.get(),
                publicManifestWriterRunning.get(),
                manifestNeedsWrite(),
                publicManifestWriteAttempts.get(),
                publicManifestWriteSuccesses.get(),
                publicManifestWriteFailures.get(),
                publicManifestLastFinalFlushStatus,
                publicManifestLastFinalFlushDurationMillis,
                publicManifestLastWriteDurationMillis,
                publicManifestLastWriteFailure,
                publicEntries.size(),
                entryBytes,
                publicPendingDeletionFiles.get(),
                publicPendingDeletionBytes.get(),
                publicReservedFiles.get(),
                publicReservedBytes.get(),
                trackedFiles,
                trackedBytes,
                publicCapacityRejections.get(),
                publicPendingDeletionRetries.get());
    }

    /**
     * Opens a public image while holding a lease on the exact cache entry.
     * Maintenance and replacement both honor this lease, so a slow servlet
     * response cannot lose its backing file halfway through transfer.
     */
    public PublicImageHandle openPublicImage(String url, HttpServletRequest request) {
        publicImage(url, request);
        String canonical = canonicalPublicUrl(url);
        String key = SecureUtil.sha256(canonical);
        synchronized (publicKeyLock(key)) {
            PublicEntry entry = publicEntries.get(key);
            if (!isUsable(entry)) {
                throw new IllegalStateException("public image became unavailable");
            }
            entry.activeReaders().incrementAndGet();
            try {
                InputStream input = Files.newInputStream(
                        entry.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                PublicImage image = new PublicImage(entry.path(), entry.contentType(), entry.etag(),
                        entry.expiresAt(), entry.length());
                return new PublicImageHandle(this, entry, image, input);
            } catch (Exception e) {
                entry.activeReaders().decrementAndGet();
                throw new IllegalStateException("open public image failed", e);
            }
        }
    }

    private void releasePublicReader(PublicEntry entry) {
        entry.activeReaders().decrementAndGet();
    }

    private void loadPublicImage(String canonical, String key, CompletableFuture<PublicEntry> future) {
        try {
            if (closed) {
                throw new ImageCacheClosedException();
            }
            // The flight can be installed after another producer has already
            // published and removed its own flight. Re-read the cache under
            // the key lock before touching the network so that race does not
            // cause a duplicate external fetch.
            synchronized (publicKeyLock(key)) {
                PublicEntry current = publicEntries.get(key);
                if (isUsable(current)) {
                    future.complete(current);
                    return;
                }
                FailureEntry failure = publicFailures.get(key);
                if (failure != null && failure.retryAt() > System.currentTimeMillis()) {
                    future.completeExceptionally(new UpstreamServiceException(
                            "image source is temporarily unavailable; retry later",
                            null,
                            Math.max(1, (failure.retryAt() - System.currentTimeMillis() + 999) / 1000)));
                    return;
                }
            }
            SafeImageFetcher.FetchedImage fetched = SafeImageFetcher.fetch(canonical, ConfigUtil.snapshot());
            if (closed) {
                throw new ImageCacheClosedException();
            }
            Path root = publicRoot();
            Files.createDirectories(root);
            verifyCacheRoot(root);
            String extension = extension(fetched.contentType());
            Path target = root.resolve(key + extension).normalize();
            byte[] bytes = fetched.bytes();
            long now = System.currentTimeMillis();
            PublicEntry entry = new PublicEntry(
                    key,
                    canonical,
                    target,
                    fetched.contentType(),
                    etag(bytes),
                    now,
                    now + PUBLIC_TTL,
                    bytes.length);
            PublicEntry previous = publicEntries.get(key);
            if (previous != null && previous.activeReaders().get() > 0) {
                throw new PublicImageBusyException();
            }
            boolean reservation = false;
            if (!ensurePublicCapacity(previous, entry.length(), key)) {
                publicCapacityRejections.incrementAndGet();
                throw new PublicCacheCapacityException();
            }
            reservation = true;
            PendingDeletionReservation pendingReservation = PendingDeletionReservation.none();
            if (previous != null && !previous.path().equals(entry.path())) {
                pendingReservation = reservePendingDeletion(key, previous.path(), previous.length());
                if (!pendingReservation.admitted()) {
                    releasePublicReservation(entry.length());
                    reservation = false;
                    throw publicCacheMaintenancePausedException();
                }
                if (pendingReservation.added()) markPublicManifestDirty();
            }
            Path temporary = null;
            boolean published = false;
            try {
                if (publicCacheMaintenancePaused.get()) {
                    throw publicCacheMaintenancePausedException();
                }
                temporary = Files.createTempFile(root, ".public-image-", ".part");
                fetched.writeTo(temporary);
                // A stale maintenance pass and a new publisher must serialize
                // around both the target move and the map replacement. If the
                // move happened before this lock, the stale pass could delete
                // the newly published file at the same key.
                synchronized (publicKeyLock(key)) {
                    if (closed) {
                        throw new ImageCacheClosedException();
                    }
                    synchronized (publicManifestStateLock) {
                        if (publicLifecycle.get() != PublicLifecycle.OPEN) {
                            throw new ImageCacheClosedException();
                        }
                        previous = publicEntries.get(key);
                        if (previous != null && previous.activeReaders().get() > 0) {
                            throw new PublicImageBusyException();
                        }
                    }
                    reservation = true;
                    try {
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    synchronized (publicManifestStateLock) {
                        previous = publicEntries.put(key, entry);
                    }
                    if (previous != null && !previous.path().equals(entry.path())) {
                        try {
                            Files.deleteIfExists(previous.path());
                            if (pendingReservation.admitted()) {
                                if (removePendingDeletion(pendingReservation.identity(),
                                        pendingReservation.pending())) {
                                    markPublicManifestDirty();
                                }
                            }
                        } catch (IOException e) {
                            // The pre-admitted old path remains tracked and is
                            // retried by maintenance; do not drop it at the
                            // replacement boundary.
                            org.slf4j.LoggerFactory.getLogger(ImageCacheService.class)
                                    .warn("public image cleanup deferred for {}: {}",
                                            previous.path(), e.getMessage());
                        }
                    }
                    published = true;
                }
                releasePublicReservation(entry.length());
                reservation = false;
                publicFailures.remove(key);
                trimPublicEntries();
                markPublicManifestDirty();
                future.complete(entry);
            } finally {
                if (reservation && !published) releasePublicReservation(fetched.bytes().length);
                if (!published && pendingReservation.added()
                        && removePendingDeletion(pendingReservation.identity(), pendingReservation.pending())) {
                    markPublicManifestDirty();
                }
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (IOException e) {
                        publicCacheMaintenancePaused.set(true);
                        warnPublicCacheMaintenancePaused();
                    }
                }
            }
        } catch (PublicImageBusyException e) {
            future.completeExceptionally(new UpstreamServiceException(
                    "image cache entry is being read; retry later", e, PUBLIC_BUSY_RETRY_SECONDS));
        } catch (PublicCacheMaintenancePausedException e) {
            future.completeExceptionally(new UpstreamServiceException(
                    "image cache maintenance is paused; check cache permissions or disk",
                    e, PUBLIC_BUSY_RETRY_SECONDS));
        } catch (PublicCacheCapacityException e) {
            future.completeExceptionally(new UpstreamServiceException(
                    "image cache capacity reached; retry later", e, PUBLIC_BUSY_RETRY_SECONDS));
        } catch (ImageCacheClosedException e) {
            future.completeExceptionally(new UpstreamServiceException(
                    "image cache is closed", e, 0));
        } catch (IOException | RuntimeException e) {
            RuntimeException failure = e instanceof UpstreamServiceException upstream
                    && upstream.retryAfterSeconds() > 0
                    ? upstream
                    : new UpstreamServiceException(
                    "image fetch failed", e, PUBLIC_FAILURE_TTL_MILLIS / 1000);
            publicFailures.put(key, new FailureEntry(
                    System.currentTimeMillis() + PUBLIC_FAILURE_TTL_MILLIS,
                    failureCategory(failure)));
            trimPublicFailures();
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
            boolean needsRepair = false;
            try {
                if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
                    PublicManifest value = GsonStatic.fromJson(Files.readString(manifest), PublicManifest.class);
                    for (PublicManifestEntry item : value == null || value.entries() == null
                            ? List.<PublicManifestEntry>of() : value.entries()) {
                        if (item == null || item.key() == null || item.url() == null
                                || item.contentType() == null) {
                            needsRepair = true;
                            continue;
                        }
                        Path path = publicRoot().resolve(item.key() + extension(item.contentType())).normalize();
                        if (!path.startsWith(publicRoot()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                                || Files.isSymbolicLink(path)) {
                            needsRepair = true;
                            continue;
                        }
                        synchronized (publicManifestStateLock) {
                            publicEntries.put(item.key(), new PublicEntry(
                                    item.key(), item.url(), path, item.contentType(), item.etag(),
                                    item.fetchedAt(), item.expiresAt(), item.length()));
                        }
                    }
                    List<PendingDeletion> pendingItems = value == null || value.pendingDeletions() == null
                            ? List.of() : value.pendingDeletions();
                    if (pendingItems.size() >= PUBLIC_PENDING_MAX_FILES) {
                        publicCacheMaintenancePaused.set(true);
                        warnPublicCacheMaintenancePaused();
                    }
                    for (PendingDeletion item : pendingItems) {
                        if (item == null || item.key() == null || item.path() == null) {
                            needsRepair = true;
                            continue;
                        }
                        Path path;
                        try {
                            path = Path.of(item.path()).toAbsolutePath().normalize();
                        } catch (RuntimeException e) {
                            needsRepair = true;
                            continue;
                        }
                        if (!path.startsWith(publicRoot()) || Files.isSymbolicLink(path)
                                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                            needsRepair = true;
                            continue;
                        }
                        String identity = pendingDeletionKey(item.key(), path);
                        long length = Math.max(0, item.length());
                        if (length == 0) {
                            try {
                                length = Files.size(path);
                            } catch (IOException ignored) {
                                // An unreadable legacy size consumes the whole budget conservatively.
                                length = PUBLIC_MAX_BYTES;
                                needsRepair = true;
                            }
                        }
                        PendingDeletion pending = new PendingDeletion(item.key(), path.toString(),
                                length, Math.max(0, item.attempts()),
                                Math.max(0, item.nextRetryAt()));
                        synchronized (publicManifestStateLock) {
                            if (publicPendingDeletions.containsKey(identity)) {
                                needsRepair = true;
                            } else {
                                publicPendingDeletions.put(identity, pending);
                                publicPendingDeletionFiles.incrementAndGet();
                                publicPendingDeletionBytes.addAndGet(pending.length());
                            }
                        }
                    }
                    if (publicPendingDeletionFiles.get() >= PUBLIC_PENDING_MAX_FILES) {
                        publicCacheMaintenancePaused.set(true);
                        warnPublicCacheMaintenancePaused();
                    }
                    if (!publicCacheMaintenancePaused.get() && trimPublicEntries()) needsRepair = true;
                }
                if (needsRepair && !publicCacheMaintenancePaused.get()) markPublicManifestDirty();
            } catch (IOException | RuntimeException ignored) {
                // A corrupt optional image manifest must never prevent startup.
                synchronized (publicManifestStateLock) {
                    publicEntries.clear();
                    publicPendingDeletions.clear();
                    publicPendingDeletionBytes.set(0);
                    publicPendingDeletionFiles.set(0);
                }
            } finally {
                publicManifestLoaded = true;
            }
        }
    }

    private void markPublicManifestDirty() {
        synchronized (publicManifestStateLock) {
            publicManifestDirty = true;
            publicManifestRevision.incrementAndGet();
        }
        if (!publicCacheMaintenancePaused.get()) scheduleManifestWriter();
    }

    private void scheduleManifestWriter() {
        if (publicLifecycle.get() != PublicLifecycle.OPEN
                || publicCacheMaintenancePaused.get() || publicManifestWriteScheduled.get()) return;
        if (!publicManifestWriteScheduled.compareAndSet(false, true)) return;
        try {
            publicManifestExecutor.execute(() -> runManifestWriter(MANIFEST_RETRY_INITIAL_MILLIS));
        } catch (RejectedExecutionException e) {
            publicManifestWriteScheduled.set(false);
        }
    }

    private void runManifestWriter(long retryDelayMillis) {
        if (!publicManifestWriterLock.tryLock()) {
            if (publicLifecycle.get() == PublicLifecycle.OPEN) {
                publicManifestWriteScheduled.set(false);
                scheduleManifestWriter();
            }
            return;
        }
        publicManifestWriterRunning.set(true);
        try {
            if (publicLifecycle.get() == PublicLifecycle.CLOSED
                    || publicCacheMaintenancePaused.get()) {
                publicManifestWriteScheduled.set(false);
                return;
            }
            ManifestWriteResult result = writeManifestPass();
            if (!result.success()) {
                long nextDelay = Math.min(MANIFEST_RETRY_MAX_MILLIS,
                        Math.max(MANIFEST_RETRY_INITIAL_MILLIS, retryDelayMillis * 2));
                submitManifestWriter(nextDelay);
                return;
            }
            if (manifestNeedsWrite() && publicLifecycle.get() == PublicLifecycle.OPEN) {
                submitManifestWriter(MANIFEST_RETRY_INITIAL_MILLIS);
            } else {
                releaseManifestWriterAndRecheck();
            }
        } finally {
            publicManifestWriterRunning.set(false);
            publicManifestWriterLock.unlock();
        }
    }

    private ManifestWriteResult writeManifestPass() {
        ManifestSnapshot snapshot;
        synchronized (publicManifestStateLock) {
            if (!publicManifestDirty && publicManifestRevision.get() == publicManifestPersistedRevision) {
                return new ManifestWriteResult(true, false, publicManifestPersistedRevision);
            }
            snapshot = snapshotPublicManifestLocked(publicManifestRevision.get());
        }
        long started = System.nanoTime();
        publicManifestWriteAttempts.incrementAndGet();
        boolean success = persistPublicManifestSnapshot(snapshot);
        publicManifestLastWriteDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started);
        if (success) {
            publicManifestWriteSuccesses.incrementAndGet();
            synchronized (publicManifestStateLock) {
                publicManifestPersistedRevision = Math.max(
                        publicManifestPersistedRevision, snapshot.revision());
                publicManifestDirty = publicManifestRevision.get() != publicManifestPersistedRevision;
            }
        } else {
            publicManifestWriteFailures.incrementAndGet();
        }
        return new ManifestWriteResult(success, true, snapshot.revision());
    }

    private ManifestSnapshot snapshotPublicManifestLocked(long revision) {
        List<PublicManifestEntry> entries = publicEntries.values().stream()
                .map(item -> new PublicManifestEntry(item.key(), item.url(), item.contentType(),
                        item.etag(), item.fetchedAt(), item.expiresAt(), item.length()))
                .sorted(Comparator.comparing(PublicManifestEntry::fetchedAt))
                .toList();
        List<PendingDeletion> pendingDeletions = publicPendingDeletions.values().stream().toList();
        return new ManifestSnapshot(revision, entries, pendingDeletions);
    }

    private boolean persistPublicManifestSnapshot(ManifestSnapshot snapshot) {
        try {
            Path root = publicRoot();
            Files.createDirectories(root);
            verifyCacheRoot(root);
            publicManifestPersistence.write(root, GsonStatic.toJson(new PublicManifest(
                    snapshot.entries(), snapshot.pendingDeletions())));
            return true;
        } catch (IOException | RuntimeException e) {
            // Public caching is an acceleration layer, not a reason to fail a request.
            publicManifestLastWriteFailure = e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage();
            return false;
        }
    }

    private static void writeManifestFile(Path root, String json) throws IOException {
        Path temporary = Files.createTempFile(root, ".public-manifest-", ".part");
        try {
            Files.writeString(temporary, json);
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
    }

    private boolean manifestNeedsWrite() {
        synchronized (publicManifestStateLock) {
            return publicManifestDirty
                    || publicManifestRevision.get() != publicManifestPersistedRevision;
        }
    }

    private void submitManifestWriter(long delayMillis) {
        if (publicLifecycle.get() != PublicLifecycle.OPEN || publicCacheMaintenancePaused.get()) {
            publicManifestWriteScheduled.set(false);
            return;
        }
        try {
            if (delayMillis <= 0) {
                publicManifestExecutor.execute(() -> runManifestWriter(
                        MANIFEST_RETRY_INITIAL_MILLIS));
            } else {
                publicManifestExecutor.schedule(() -> runManifestWriter(delayMillis),
                        delayMillis, TimeUnit.MILLISECONDS);
            }
        } catch (RejectedExecutionException e) {
            publicManifestWriteScheduled.set(false);
        }
    }

    private void releaseManifestWriterAndRecheck() {
        publicManifestWriteScheduled.set(false);
        if (manifestNeedsWrite()) scheduleManifestWriter();
    }

    private void flushPublicManifestOnClose() {
        long started = System.nanoTime();
        long deadline = started + TimeUnit.MILLISECONDS.toNanos(MANIFEST_CLOSE_TIMEOUT_MILLIS);
        CompletableFuture<String> finalFlush = new CompletableFuture<>();
        publicManifestLastFinalFlushStatus = "pending";
        try {
            if (publicManifestExecutor instanceof java.util.concurrent.ScheduledThreadPoolExecutor executor) {
                // A delayed retry must not make shutdown wait for the entire
                // backoff period. The current writer, if any, is left alone;
                // the immediate final task runs after it on the same executor.
                executor.getQueue().clear();
            }
            publicManifestExecutor.execute(() -> runFinalManifestFlush(finalFlush, deadline));
            publicManifestExecutor.shutdown();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                publicManifestLastFinalFlushStatus = "timeout";
            } else {
                finalFlush.get(remaining, TimeUnit.NANOSECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publicManifestLastFinalFlushStatus = "timeout";
        } catch (java.util.concurrent.TimeoutException e) {
            publicManifestLastFinalFlushStatus = "timeout";
        } catch (ExecutionException | RejectedExecutionException e) {
            publicManifestLastFinalFlushStatus = "failed";
        } finally {
            publicManifestLastFinalFlushDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
            publicManifestWriteScheduled.set(false);
        }
    }

    private void runFinalManifestFlush(CompletableFuture<String> result, long deadline) {
        boolean locked = false;
        try {
            if (publicLifecycle.get() == PublicLifecycle.CLOSED) {
                publicManifestLastFinalFlushStatus = "timeout";
                result.complete("timeout");
                return;
            }
            if (publicCacheMaintenancePaused.get()) {
                publicManifestLastFinalFlushStatus = "paused";
                result.complete("paused");
                return;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || !publicManifestWriterLock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
                publicManifestLastFinalFlushStatus = "timeout";
                result.complete("timeout");
                return;
            }
            locked = true;
            publicManifestWriterRunning.set(true);
            if (!manifestNeedsWrite()) {
                publicManifestLastFinalFlushStatus = "success";
                result.complete("success");
                return;
            }
            if (System.nanoTime() >= deadline) {
                publicManifestLastFinalFlushStatus = "timeout";
                result.complete("timeout");
                return;
            }
            ManifestWriteResult write = writeManifestPass();
            if (System.nanoTime() >= deadline || publicLifecycle.get() == PublicLifecycle.CLOSED) {
                publicManifestLastFinalFlushStatus = "timeout";
                result.complete("timeout");
            } else {
                publicManifestLastFinalFlushStatus = write.success() ? "success" : "failed";
                result.complete(publicManifestLastFinalFlushStatus);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publicManifestLastFinalFlushStatus = "timeout";
            result.complete("timeout");
        } catch (RuntimeException e) {
            publicManifestLastFinalFlushStatus = "failed";
            result.complete("failed");
        } finally {
            publicManifestWriterRunning.set(false);
            if (locked) publicManifestWriterLock.unlock();
        }
    }

    private boolean trimPublicEntries() {
        return trimPublicEntries(0, null, false);
    }

    private boolean trimPublicEntries(long incomingLength, String protectedKey) {
        return trimPublicEntries(incomingLength, protectedKey, true);
    }

    private boolean trimPublicEntries(long incomingLength, String protectedKey, boolean incomingFile) {
        if (publicLifecycle.get() != PublicLifecycle.OPEN) return false;
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (PublicEntry entry : List.copyOf(publicEntries.values())) {
            if (protectedKey != null && protectedKey.equals(entry.key())) continue;
            if (entry.expiresAt() > now) continue;
            changed |= removePublicEntry(entry);
        }
        if (hasPublicCapacity(incomingLength, incomingFile)) return changed;
        List<PublicEntry> oldest = publicEntries.values().stream()
                .filter(entry -> protectedKey == null || !protectedKey.equals(entry.key()))
                .sorted(Comparator.comparingLong(PublicEntry::fetchedAt))
                .toList();
        for (PublicEntry entry : oldest) {
            if (hasPublicCapacity(incomingLength, incomingFile)) break;
            changed |= removePublicEntry(entry);
        }
        return changed;
    }

    private boolean hasPublicCapacity(long incomingLength, boolean incomingFile) {
        synchronized (publicManifestStateLock) {
            return trackedBytesLocked() + Math.max(0, incomingLength) <= PUBLIC_MAX_BYTES
                    && trackedFilesLocked() + (incomingFile ? 1 : 0) <= PUBLIC_MAX_ENTRIES;
        }
    }

    private boolean ensurePublicCapacity(PublicEntry replacing, long incomingLength, String protectedKey) {
        if (publicCacheMaintenancePaused.get()) return false;
        if (reservePublicCapacity(replacing, incomingLength)) return true;
        if (publicCacheMaintenancePaused.get()) return false;
        trimPublicEntries(incomingLength, protectedKey);
        return reservePublicCapacity(replacing, incomingLength);
    }

    private boolean reservePublicCapacity(PublicEntry replacing, long incomingLength) {
        if (incomingLength < 0 || incomingLength > PUBLIC_MAX_BYTES) return false;
        synchronized (publicManifestStateLock) {
            if (publicCacheMaintenancePaused.get()
                    || publicPendingDeletionFiles.get() >= PUBLIC_PENDING_MAX_FILES) {
                publicCacheMaintenancePaused.set(true);
                warnPublicCacheMaintenancePaused();
                return false;
            }
            long trackedBytes = trackedBytesLocked();
            int trackedFiles = trackedFilesLocked();
            if (trackedBytes + incomingLength > PUBLIC_MAX_BYTES
                    || trackedFiles + 1 > PUBLIC_MAX_ENTRIES) {
                return false;
            }
            publicReservedBytes.addAndGet(incomingLength);
            publicReservedFiles.incrementAndGet();
            return true;
        }
    }

    private boolean isPublicCacheAdmissionPaused() {
        if (publicCacheMaintenancePaused.get()) return true;
        synchronized (publicManifestStateLock) {
            if (publicPendingDeletionFiles.get() < PUBLIC_PENDING_MAX_FILES) return false;
            publicCacheMaintenancePaused.set(true);
            warnPublicCacheMaintenancePaused();
            return true;
        }
    }

    private void warnPublicCacheMaintenancePaused() {
        long now = System.currentTimeMillis();
        long previous = publicCacheMaintenanceWarningAt.get();
        if (now - previous < PUBLIC_MAINTENANCE_WARNING_INTERVAL_MILLIS
                || !publicCacheMaintenanceWarningAt.compareAndSet(previous, now)) return;
        org.slf4j.LoggerFactory.getLogger(ImageCacheService.class)
                .warn("public image cache maintenance is paused; pending cleanup reached its limit; "
                        + "check cache permissions, disk space, and open file handles");
    }

    private static PublicCacheMaintenancePausedException publicCacheMaintenancePausedException() {
        return new PublicCacheMaintenancePausedException();
    }

    private void releasePublicReservation(long length) {
        synchronized (publicManifestStateLock) {
            publicReservedBytes.addAndGet(-length);
            publicReservedFiles.updateAndGet(value -> Math.max(0, value - 1));
        }
    }

    private long publicTrackedBytes() {
        synchronized (publicManifestStateLock) {
            return trackedBytesLocked();
        }
    }

    private int publicTrackedFiles() {
        synchronized (publicManifestStateLock) {
            return trackedFilesLocked();
        }
    }

    private long trackedBytesLocked() {
        return trackedFileLengthsLocked().values().stream().mapToLong(Long::longValue).sum()
                + publicReservedBytes.get();
    }

    private int trackedFilesLocked() {
        return trackedFileLengthsLocked().size() + publicReservedFiles.get();
    }

    private Map<String, Long> trackedFileLengthsLocked() {
        Map<String, Long> lengths = new HashMap<>();
        publicEntries.values().forEach(entry -> lengths.merge(
                entry.path().toAbsolutePath().normalize().toString(), entry.length(), Math::max));
        publicPendingDeletions.values().forEach(pending -> lengths.merge(
                pending.path(), pending.length(), Math::max));
        return lengths;
    }

    private boolean removePublicEntry(PublicEntry entry) {
        if (entry == null) return false;
        synchronized (publicKeyLock(entry.key())) {
            Path expected = publicRoot().resolve(
                    entry.key() + extension(entry.contentType())).normalize();
            synchronized (publicManifestStateLock) {
                if (publicLifecycle.get() != PublicLifecycle.OPEN
                        || publicEntries.get(entry.key()) != entry
                        || entry.activeReaders().get() > 0
                        || !expected.equals(entry.path().toAbsolutePath().normalize())
                        || !expected.startsWith(publicRoot())
                        || !publicEntries.remove(entry.key(), entry)) {
                    return false;
                }
                // Record the removal before filesystem I/O so close cannot
                // flush a snapshot that still advertises the evicted entry.
                markPublicManifestDirty();
            }
            try {
                Files.deleteIfExists(expected);
                String identity = pendingDeletionKey(entry.key(), expected);
                PendingDeletion pending = publicPendingDeletions.get(identity);
                if (pending != null && removePendingDeletion(identity, pending)) {
                    markPublicManifestDirty();
                }
                return true;
            } catch (IOException e) {
                // Restore the exact entry while holding the same key lock so
                // a publisher cannot replace it between remove and restore.
                synchronized (publicManifestStateLock) {
                    publicEntries.putIfAbsent(entry.key(), entry);
                }
                if (!rememberPendingDeletion(entry.key(), expected, entry.length())) {
                    markPublicManifestDirty();
                }
                org.slf4j.LoggerFactory.getLogger(ImageCacheService.class)
                        .warn("public image cleanup deferred for {}: {}", entry.path(), e.getMessage());
                return false;
            }
        }
    }

    private PendingDeletionReservation reservePendingDeletion(String key, Path path, long length) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = publicRoot();
        if (!normalized.startsWith(root) || normalized.equals(root.resolve(PUBLIC_MANIFEST))) {
            return PendingDeletionReservation.none();
        }
        String identity = pendingDeletionKey(key, normalized);
        PendingDeletion pending;
        synchronized (publicManifestStateLock) {
            PendingDeletion existing = publicPendingDeletions.get(identity);
            if (existing != null) {
                return new PendingDeletionReservation(true, false, identity, existing);
            }
            if (publicCacheMaintenancePaused.get()
                    || publicPendingDeletionFiles.get() >= PUBLIC_PENDING_MAX_FILES) {
                publicCacheMaintenancePaused.set(true);
                warnPublicCacheMaintenancePaused();
                return PendingDeletionReservation.none();
            }
            pending = new PendingDeletion(key, normalized.toString(),
                    Math.max(0, length), 0, 0);
            publicPendingDeletions.put(identity, pending);
            publicPendingDeletionFiles.incrementAndGet();
            publicPendingDeletionBytes.addAndGet(pending.length());
        }
        return new PendingDeletionReservation(true, true, identity, pending);
    }

    private boolean rememberPendingDeletion(String key, Path path, long length) {
        PendingDeletionReservation reservation = reservePendingDeletion(key, path, length);
        if (reservation.added()) markPublicManifestDirty();
        return reservation.admitted();
    }

    private boolean retryPendingDeletions() {
        boolean changed = false;
        Path root = publicRoot();
        long now = System.currentTimeMillis();
        List<Map.Entry<String, PendingDeletion>> candidates = publicPendingDeletions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (candidates.isEmpty()) return false;
        int limit = Math.min(PUBLIC_PENDING_SCAN_LIMIT, candidates.size());
        int start = Math.floorMod(publicPendingDeletionScanCursor.get(), candidates.size());
        for (int offset = 0; offset < limit; offset++) {
            Map.Entry<String, PendingDeletion> item = candidates.get((start + offset) % candidates.size());
            PendingDeletion pending = item.getValue();
            if (pending.nextRetryAt() > now) continue;
            Path path;
            try {
                path = Path.of(pending.path()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                changed |= removePendingDeletion(item.getKey(), pending);
                continue;
            }
            if (!path.startsWith(root) || Files.isSymbolicLink(path)) {
                changed |= removePendingDeletion(item.getKey(), pending);
                continue;
            }
            synchronized (publicKeyLock(pending.key())) {
                boolean pathInUse;
                synchronized (publicManifestStateLock) {
                    pathInUse = publicEntries.values().stream()
                            .anyMatch(current -> current.path().toAbsolutePath().normalize().equals(path));
                }
                if (pathInUse) {
                    changed |= removePendingDeletion(item.getKey(), pending);
                    continue;
                }
                try {
                    Files.deleteIfExists(path);
                    changed |= removePendingDeletion(item.getKey(), pending);
                } catch (IOException ignored) {
                    int attempts = pending.attempts() + 1;
                    long delay = Math.min(PUBLIC_PENDING_RETRY_MAX_MILLIS,
                            PUBLIC_PENDING_RETRY_INITIAL_MILLIS * (1L << Math.min(5, attempts - 1)));
                    PendingDeletion retried = new PendingDeletion(
                            pending.key(), pending.path(), pending.length(), attempts, now + delay);
                    boolean replaced;
                    synchronized (publicManifestStateLock) {
                        replaced = publicPendingDeletions.replace(item.getKey(), pending, retried);
                    }
                    if (replaced) {
                        publicPendingDeletionRetries.incrementAndGet();
                        changed = true;
                    }
                }
            }
        }
        publicPendingDeletionScanCursor.set((start + limit) % candidates.size());
        if (changed) markPublicManifestDirty();
        return changed;
    }

    private boolean removePendingDeletion(String identity, PendingDeletion pending) {
        synchronized (publicManifestStateLock) {
            if (!publicPendingDeletions.remove(identity, pending)) return false;
            publicPendingDeletionFiles.updateAndGet(value -> Math.max(0, value - 1));
            publicPendingDeletionBytes.addAndGet(-pending.length());
            if (publicPendingDeletionFiles.get() < PUBLIC_PENDING_MAX_FILES) {
                publicCacheMaintenancePaused.set(false);
            }
            return true;
        }
    }

    private static String pendingDeletionKey(String key, Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private void trimPublicFailures() {
        long now = System.currentTimeMillis();
        publicFailures.entrySet().removeIf(entry -> entry.getValue().retryAt() <= now);
        if (publicFailures.size() <= PUBLIC_FAILURE_MAX_ENTRIES) {
            return;
        }
        List<Map.Entry<String, FailureEntry>> oldest = publicFailures.entrySet().stream()
                .sorted(Comparator.comparingLong(entry -> entry.getValue().retryAt()))
                .toList();
        int removeCount = publicFailures.size() - PUBLIC_FAILURE_MAX_ENTRIES;
        for (int index = 0; index < removeCount && index < oldest.size(); index++) {
            Map.Entry<String, FailureEntry> entry = oldest.get(index);
            publicFailures.remove(entry.getKey(), entry.getValue());
        }
    }

    private static String failureCategory(Throwable failure) {
        if (failure instanceof java.util.concurrent.TimeoutException) return "timeout";
        if (failure instanceof java.net.ConnectException) return "connect";
        if (failure instanceof UpstreamServiceException) return "upstream";
        return "fetch";
    }

    private void maintainPublicCache() {
        try {
            loadPublicManifest();
            trimPublicFailures();
            trimPublicEntries();
            retryPendingDeletions();
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

    private Object publicKeyLock(String key) {
        return publicKeyLocks[Math.floorMod(key.hashCode(), publicKeyLocks.length)];
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

    public record PublicCacheDiagnostics(
            String lifecycle,
            boolean maintenancePaused,
            long currentRevision,
            long persistedRevision,
            boolean writerScheduled,
            boolean writerRunning,
            boolean dirty,
            long writeAttempts,
            long writeSuccesses,
            long writeFailures,
            String finalFlushStatus,
            long finalFlushDurationMillis,
            long lastWriteDurationMillis,
            String lastWriteFailure,
            int entryFiles,
            long entryBytes,
            int pendingDeletionFiles,
            long pendingDeletionBytes,
            int reservedFiles,
            long reservedBytes,
            int trackedFiles,
            long trackedBytes,
            long capacityRejections,
            long pendingDeletionRetries) {
    }

    public static final class PublicImageHandle implements AutoCloseable {
        private final ImageCacheService owner;
        private final PublicEntry entry;
        private final PublicImage image;
        private final InputStream input;
        private boolean closed;

        private PublicImageHandle(ImageCacheService owner, PublicEntry entry,
                                  PublicImage image, InputStream input) {
            this.owner = owner;
            this.entry = entry;
            this.image = image;
            this.input = input;
        }

        public PublicImage image() {
            return image;
        }

        public InputStream inputStream() {
            return input;
        }

        @Override
        public synchronized void close() throws IOException {
            if (closed) return;
            closed = true;
            try {
                input.close();
            } finally {
                owner.releasePublicReader(entry);
            }
        }
    }

    private record Entry(Path path, String contentType, String binding, String sourceKey, long expiresAt) {
    }

    private record PublicEntry(String key, String url, Path path, String contentType,
                               String etag, long fetchedAt, long expiresAt, long length,
                               AtomicInteger activeReaders) {
        private PublicEntry(String key, String url, Path path, String contentType,
                            String etag, long fetchedAt, long expiresAt, long length) {
            this(key, url, path, contentType, etag, fetchedAt, expiresAt, length, new AtomicInteger());
        }
    }

    private record PublicManifest(List<PublicManifestEntry> entries,
                                 List<PendingDeletion> pendingDeletions) {
        private PublicManifest(List<PublicManifestEntry> entries) {
            this(entries, List.of());
        }
    }

    private record PublicManifestEntry(String key, String url, String contentType,
                                       String etag, long fetchedAt, long expiresAt, long length) {
    }

    private record FailureEntry(long retryAt, String reason) {
    }

    private record PendingDeletion(String key, String path, long length,
                                   int attempts, long nextRetryAt) {
    }

    private record PendingDeletionReservation(boolean admitted, boolean added,
                                              String identity, PendingDeletion pending) {
        private static PendingDeletionReservation none() {
            return new PendingDeletionReservation(false, false, "", null);
        }
    }

    private record ManifestSnapshot(long revision,
                                    List<PublicManifestEntry> entries,
                                    List<PendingDeletion> pendingDeletions) {
    }

    private record ManifestWriteResult(boolean success, boolean attempted, long revision) {
    }

    @FunctionalInterface
    interface PublicManifestPersistence {
        void write(Path root, String json) throws IOException;
    }

    private enum PublicLifecycle {
        OPEN,
        CLOSING,
        CLOSED
    }

    private static final class PublicImageBusyException extends RuntimeException {
    }

    private static final class PublicCacheCapacityException extends RuntimeException {
    }

    private static final class PublicCacheMaintenancePausedException extends RuntimeException {
    }

    private static final class ImageCacheClosedException extends RuntimeException {
    }
}
