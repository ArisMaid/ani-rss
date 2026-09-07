package ani.rss.service;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheServiceTest {

    @Test
    void enrichmentReturnsImmediatelyWhileCoverIndexRefreshes() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CacheService service = new CacheService(() -> {
            started.countDown();
            release.await(2, TimeUnit.SECONDS);
            return new JsonObject();
        });
        try {
            CacheService.CoverLookup first = assertTimeoutPreemptively(
                    Duration.ofMillis(500), service::getBgmCoverForEnrichmentSnapshot);

            assertFalse(first.loaded());
            assertTrue(started.await(1, TimeUnit.SECONDS));

            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            CacheService.CoverLookup latest = first;
            while (!latest.loaded() && System.nanoTime() < deadline) {
                Thread.sleep(10);
                latest = service.getBgmCoverForEnrichmentSnapshot();
            }
            assertTrue(latest.loaded(), "a successful empty index is still a loaded snapshot");
            assertTrue(latest.entries().isEmpty());
        } finally {
            release.countDown();
            service.close();
        }
    }

    @Test
    void failedCoverRefreshIsCooledDownAndNotRetriedOnEveryCard() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        CacheService service = new CacheService(() -> {
            loads.incrementAndGet();
            failed.countDown();
            throw new IllegalStateException("synthetic cover failure");
        });
        try {
            service.getBgmCoverForEnrichmentSnapshot();
            assertTrue(failed.await(1, TimeUnit.SECONDS));
            for (int i = 0; i < 20; i++) {
                service.getBgmCoverForEnrichmentSnapshot();
            }
            assertEquals(1, loads.get());
        } finally {
            service.close();
        }
    }
}
