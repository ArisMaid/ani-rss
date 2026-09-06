package ani.rss.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Consumer;

/** Coalesces subscription refresh requests into one worker. */
final class SubscriptionDownloadQueue {
    private final Object monitor = new Object();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private final Set<String> acceptedThisRun = new LinkedHashSet<>();
    private boolean workerRunning;
    private int failedCount;
    private long finishedAt;

    boolean submit(Collection<String> subscriptionIds,
                   Executor executor,
                   Consumer<List<String>> worker) {
        Function<List<String>, Integer> adaptingWorker = batch -> {
            worker.accept(batch);
            return 0;
        };
        return submit(subscriptionIds, executor, adaptingWorker);
    }

    boolean submit(Collection<String> subscriptionIds,
                   Executor executor,
                   Function<List<String>, Integer> worker) {
        Objects.requireNonNull(subscriptionIds, "subscriptionIds");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(worker, "worker");

        synchronized (monitor) {
            for (String subscriptionId : subscriptionIds) {
                if (subscriptionId == null || subscriptionId.isBlank()) {
                    continue;
                }
                if (acceptedThisRun.add(subscriptionId)) {
                    pending.add(subscriptionId);
                }
            }
            if (!workerRunning) {
                failedCount = 0;
                finishedAt = 0;
            }
            if (workerRunning || pending.isEmpty()) {
                return false;
            }
            workerRunning = true;
        }

        try {
            executor.execute(() -> drain(worker));
            return true;
        } catch (RuntimeException | Error e) {
            synchronized (monitor) {
                workerRunning = false;
            }
            throw e;
        }
    }

    boolean isWorkerRunning() {
        synchronized (monitor) {
            return workerRunning;
        }
    }

    Status snapshot() {
        synchronized (monitor) {
            return new Status(workerRunning, failedCount, finishedAt);
        }
    }

    private void drain(Function<List<String>, Integer> worker) {
        while (true) {
            List<String> batch;
            synchronized (monitor) {
                if (pending.isEmpty()) {
                    workerRunning = false;
                    finishedAt = System.currentTimeMillis();
                    acceptedThisRun.clear();
                    return;
                }
                batch = new ArrayList<>(pending);
                pending.clear();
            }

            try {
                int failures = worker.apply(List.copyOf(batch));
                synchronized (monitor) {
                    failedCount += Math.max(0, failures);
                }
            } catch (RuntimeException | Error e) {
                synchronized (monitor) {
                    LinkedHashSet<String> retry = new LinkedHashSet<>(batch);
                    retry.addAll(pending);
                    pending.clear();
                    pending.addAll(retry);
                    failedCount += batch.size();
                    acceptedThisRun.clear();
                    acceptedThisRun.addAll(pending);
                    workerRunning = false;
                    finishedAt = System.currentTimeMillis();
                }
                throw e;
            }
        }
    }

    record Status(boolean running, int failedCount, long finishedAt) {
    }
}
