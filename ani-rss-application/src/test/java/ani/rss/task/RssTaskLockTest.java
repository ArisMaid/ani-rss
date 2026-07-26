package ani.rss.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssTaskLockTest {
    @Test
    void coalescesDuplicateRequestsAndDrainsNewIdsInTheSameWorker() {
        SubscriptionDownloadQueue queue = new SubscriptionDownloadQueue();
        List<Runnable> scheduled = new ArrayList<>();
        List<List<String>> batches = new ArrayList<>();
        AtomicReference<Consumer<List<String>>> workerRef = new AtomicReference<>();
        Consumer<List<String>> worker = batch -> {
            batches.add(batch);
            if (batches.size() == 1) {
                queue.submit(List.of("one", "two"), scheduled::add, workerRef.get());
            }
        };
        workerRef.set(worker);

        assertTrue(queue.submit(List.of("one"), scheduled::add, worker));
        assertFalse(queue.submit(List.of("one"), scheduled::add, worker));
        assertEquals(1, scheduled.size());

        scheduled.remove(0).run();

        assertEquals(List.of(List.of("one"), List.of("two")), batches);
        assertFalse(queue.isWorkerRunning());
        assertTrue(queue.submit(List.of("one"), scheduled::add, worker));
    }

    @Test
    void ignoresBlankIdsWithoutStartingAWorker() {
        SubscriptionDownloadQueue queue = new SubscriptionDownloadQueue();
        List<Runnable> scheduled = new ArrayList<>();

        assertFalse(queue.submit(List.of("", " "), scheduled::add, ignored -> { }));
        assertTrue(scheduled.isEmpty());
        assertFalse(queue.isWorkerRunning());
    }

    @Test
    void allowsRetryWhenTheExecutorRejectsStartup() {
        SubscriptionDownloadQueue queue = new SubscriptionDownloadQueue();

        assertThrows(RejectedExecutionException.class, () -> queue.submit(
                List.of("one"),
                ignored -> {
                    throw new RejectedExecutionException("injected rejection");
                },
                ignored -> { }));
        assertFalse(queue.isWorkerRunning());

        List<Runnable> scheduled = new ArrayList<>();
        assertTrue(queue.submit(List.of("one"), scheduled::add, ignored -> { }));
        assertEquals(1, scheduled.size());
    }
}
