package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.util.basic.HttpReq;
import com.google.gson.JsonObject;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class CacheService {
    private static final int COVER_REQUEST_TIMEOUT_MILLIS = 5_000;
    private static final long COVER_CACHE_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);
    private static final long COVER_FAILURE_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private final ExecutorService coverRefreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ani-rss-bgm-cover");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<CompletableFuture<JsonObject>> coverRefresh = new AtomicReference<>();
    private final CoverLoader coverLoader;
    private volatile JsonObject coverSnapshot = new JsonObject();
    private volatile long coverExpiresAt;
    private volatile long coverRetryAt;
    private volatile boolean coverLoaded;
    private volatile boolean closed;

    public CacheService() {
        this(CacheService::loadCoverIndex);
    }

    CacheService(CoverLoader coverLoader) {
        this.coverLoader = coverLoader;
    }

    /**
     * Returns the last known cover index and schedules a refresh when it is
     * stale.  The caller never waits for the cache service's network request.
     */
    public JsonObject getBgmCoverSnapshot() {
        return getBgmCoverForEnrichmentSnapshot().entries();
    }

    /**
     * Returns the last known cover index and starts at most one shared refresh.
     * Enrichment must not block the first-screen response on this optional
     * upstream, so callers inspect {@link CoverLookup#loaded()} and retry the
     * missing fields when a refresh has completed.
     */
    public JsonObject getBgmCoverForEnrichment() {
        return getBgmCoverForEnrichmentSnapshot().entries();
    }

    public CoverLookup getBgmCoverForEnrichmentSnapshot() {
        long now = System.currentTimeMillis();
        if (coverExpiresAt <= now && coverRetryAt <= now) {
            refreshBgmCoverAsync();
        }
        return new CoverLookup(copy(coverSnapshot), coverLoaded, coverExpiresAt, coverRetryAt);
    }

    private CompletableFuture<JsonObject> refreshBgmCoverAsync() {
        if (closed) {
            return CompletableFuture.completedFuture(copy(coverSnapshot));
        }
        long now = System.currentTimeMillis();
        if (coverExpiresAt > now || coverRetryAt > now) {
            return CompletableFuture.completedFuture(coverSnapshot);
        }
        CompletableFuture<JsonObject> current = coverRefresh.get();
        if (current != null) {
            return current;
        }
        CompletableFuture<JsonObject> created = new CompletableFuture<>();
        if (!coverRefresh.compareAndSet(null, created)) {
            return refreshBgmCoverAsync();
        }
        try {
            coverRefreshExecutor.execute(() -> {
                try {
                    JsonObject loaded = coverLoader.load();
                    coverSnapshot = loaded == null ? new JsonObject() : loaded;
                    coverExpiresAt = System.currentTimeMillis() + COVER_CACHE_TTL_MILLIS;
                    coverRetryAt = 0;
                    coverLoaded = true;
                    created.complete(coverSnapshot);
                } catch (Exception e) {
                    log.warn("AnimeGarden cover index refresh failed: {}", e.getMessage());
                    coverRetryAt = System.currentTimeMillis() + COVER_FAILURE_COOLDOWN_MILLIS;
                    created.complete(coverSnapshot);
                } finally {
                    coverRefresh.compareAndSet(created, null);
                }
            });
        } catch (RuntimeException e) {
            coverRefresh.compareAndSet(created, null);
            coverRetryAt = System.currentTimeMillis() + COVER_FAILURE_COOLDOWN_MILLIS;
            created.complete(coverSnapshot);
        }
        return created;
    }

    private static JsonObject copy(JsonObject value) {
        return value == null
                ? new JsonObject()
                : GsonStatic.fromJson(GsonStatic.toJson(value), JsonObject.class);
    }

    private static JsonObject loadCoverIndex() {
        return HttpReq.get("https://cache.wushuo.top/bgm/cover")
                .timeout(COVER_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    return GsonStatic.fromJson(res.body(), JsonObject.class);
                });
    }

    @PreDestroy
    void close() {
        closed = true;
        CompletableFuture<JsonObject> refresh = coverRefresh.getAndSet(null);
        if (refresh != null) refresh.complete(copy(coverSnapshot));
        coverRefreshExecutor.shutdownNow();
    }

    public record CoverLookup(JsonObject entries, boolean loaded, long expiresAt, long retryAt) {
        public CoverLookup {
            entries = copy(entries);
        }

        @Override
        public JsonObject entries() {
            return copy(entries);
        }
    }

    @FunctionalInterface
    interface CoverLoader {
        JsonObject load() throws Exception;
    }

    /** Legacy entry point retained for non-list callers. */
    public JsonObject getBgmCover() {
        return getBgmCoverForEnrichment();
    }
}
