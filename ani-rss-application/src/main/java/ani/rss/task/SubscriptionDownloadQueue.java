package ani.rss.task;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Coalesces subscription refresh requests into one worker. */
final class SubscriptionDownloadQueue {
    private final Object monitor = new Object();
    private final LinkedHashSet<String> pending = new LinkedHashSet<>();
    private final Set<String> acceptedThisRun = new LinkedHashSet<>();
    private boolean workerRunning;

    boolean submit(Collection<String> subscriptionIds,
                   Executor executor,
                   Consumer<List<String>> worker) {
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

    private void drain(Consumer<List<String>> worker) {
        while (true) {
            List<String> batch;
            synchronized (monitor) {
                if (pending.isEmpty()) {
                    workerRunning = false;
                    acceptedThisRun.clear();
                    return;
                }
                batch = new ArrayList<>(pending);
                pending.clear();
            }

            try {
                worker.accept(List.copyOf(batch));
            } catch (RuntimeException | Error e) {
                synchronized (monitor) {
                    LinkedHashSet<String> retry = new LinkedHashSet<>(batch);
                    retry.addAll(pending);
                    pending.clear();
                    pending.addAll(retry);
                    acceptedThisRun.clear();
                    acceptedThisRun.addAll(pending);
                    workerRunning = false;
                }
                throw e;
            }
        }
    }
}
