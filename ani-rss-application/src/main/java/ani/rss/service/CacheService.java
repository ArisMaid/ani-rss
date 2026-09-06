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

    private final ExecutorService coverRefreshExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ani-rss-bgm-cover");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<CompletableFuture<JsonObject>> coverRefresh = new AtomicReference<>();
    private volatile JsonObject coverSnapshot = new JsonObject();
    private volatile long coverExpiresAt;

    /**
     * Returns the last known cover index and schedules a refresh when it is
     * stale.  The caller never waits for the cache service's network request.
     */
    public JsonObject getBgmCoverSnapshot() {
        if (coverExpiresAt <= System.currentTimeMillis()) {
            refreshBgmCoverAsync();
        }
        return copy(coverSnapshot);
    }

    /**
     * Enrichment is explicitly user initiated, so it may wait for the one
     * shared index refresh.  A timeout still returns the stale/empty snapshot
     * and leaves the subject retryable; the title list is never involved.
     */
    public JsonObject getBgmCoverForEnrichment() {
        CompletableFuture<JsonObject> refresh = refreshBgmCoverAsync();
        try {
            refresh.get(COVER_REQUEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The enrichment response will mark missing covers retryable.
        }
        return copy(coverSnapshot);
    }

    private CompletableFuture<JsonObject> refreshBgmCoverAsync() {
        if (coverExpiresAt > System.currentTimeMillis()) {
            return CompletableFuture.completedFuture(coverSnapshot);
        }
        CompletableFuture<JsonObject> current = coverRefresh.get();
        if (current != null) {
            return current;
        }
        CompletableFuture<JsonObject> created = new CompletableFuture<>();
        if (!coverRefresh.compareAndSet(null, created)) {
            return coverRefresh.get();
        }
        try {
            coverRefreshExecutor.execute(() -> {
                try {
                    JsonObject loaded = HttpReq.get("https://cache.wushuo.top/bgm/cover")
                            .timeout(COVER_REQUEST_TIMEOUT_MILLIS)
                            .thenFunction(res -> {
                                HttpReq.assertStatus(res);
                                return GsonStatic.fromJson(res.body(), JsonObject.class);
                            });
                    coverSnapshot = loaded == null ? new JsonObject() : loaded;
                    coverExpiresAt = System.currentTimeMillis() + COVER_CACHE_TTL_MILLIS;
                    created.complete(coverSnapshot);
                } catch (Exception e) {
                    log.warn("AnimeGarden cover index refresh failed: {}", e.getMessage());
                    created.complete(coverSnapshot);
                } finally {
                    coverRefresh.compareAndSet(created, null);
                }
            });
        } catch (RuntimeException e) {
            coverRefresh.compareAndSet(created, null);
            created.complete(coverSnapshot);
        }
        return created;
    }

    private static JsonObject copy(JsonObject value) {
        return value == null
                ? new JsonObject()
                : GsonStatic.fromJson(GsonStatic.toJson(value), JsonObject.class);
    }

    @PreDestroy
    void close() {
        coverRefreshExecutor.shutdownNow();
    }

    /** Legacy synchronous entry point retained for non-list callers. */
    public JsonObject getBgmCover() {
        return getBgmCoverForEnrichment();
    }
}
