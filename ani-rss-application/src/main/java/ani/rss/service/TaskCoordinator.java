package ani.rss.service;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/** Coordinates background task state and exclusive maintenance operations. */
@Component
public final class TaskCoordinator {
    private final ReentrantLock maintenanceLock = new ReentrantLock(true);
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    public State state() {
        return state.get();
    }

    public MaintenanceLease enterMaintenance(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        boolean acquired;
        try {
            acquired = maintenanceLock.tryLock(Math.max(0, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for maintenance lock", e);
        }
        if (!acquired) {
            throw new IllegalStateException("maintenance lock timeout");
        }
        State previous = state.getAndSet(State.MAINTENANCE);
        return new MaintenanceLease(previous);
    }

    public void requireStartAllowed() {
        State current = state.get();
        if ((current == State.MAINTENANCE || current == State.FAILED) &&
                !maintenanceLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("background tasks are disabled during maintenance");
        }
    }

    public void taskStarted() {
        if (state.get() != State.MAINTENANCE) {
            state.set(State.RUNNING);
        }
    }

    public void taskStopped() {
        if (state.get() != State.MAINTENANCE) {
            state.set(State.STOPPED);
        }
    }

    public void taskStopFailed() {
        state.set(State.FAILED);
    }

    public enum State {
        STOPPED,
        RUNNING,
        MAINTENANCE,
        FAILED
    }

    public final class MaintenanceLease implements AutoCloseable {
        private final State previous;
        private boolean completed;
        private boolean closed;

        private MaintenanceLease(State previous) {
            this.previous = previous;
        }

        public void complete(boolean tasksRunning) {
            requireOwner();
            state.set(tasksRunning ? State.RUNNING : State.STOPPED);
            completed = true;
        }

        public void fail() {
            requireOwner();
            state.set(State.FAILED);
            completed = true;
        }

        public State previousState() {
            return previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            requireOwner();
            if (!completed) {
                state.set(State.FAILED);
            }
            closed = true;
            maintenanceLock.unlock();
        }

        private void requireOwner() {
            if (!maintenanceLock.isHeldByCurrentThread()) {
                throw new IllegalStateException("maintenance lease used by non-owner thread");
            }
        }
    }
}
