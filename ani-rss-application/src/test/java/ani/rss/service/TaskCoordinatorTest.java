package ani.rss.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskCoordinatorTest {
    @Test
    void maintenanceLeaseBlocksNonOwnerStartsAndRestoresState() throws Exception {
        TaskCoordinator coordinator = new TaskCoordinator();
        coordinator.taskStarted();
        assertEquals(TaskCoordinator.State.RUNNING, coordinator.state());

        try (TaskCoordinator.MaintenanceLease lease = coordinator.enterMaintenance(Duration.ofSeconds(1))) {
            assertEquals(TaskCoordinator.State.MAINTENANCE, coordinator.state());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    coordinator.requireStartAllowed();
                } catch (Throwable throwable) {
                    failure.set(throwable);
                }
            });
            other.start();
            other.join();
            assertEquals(IllegalStateException.class, failure.get().getClass());
            lease.complete(true);
        }

        assertEquals(TaskCoordinator.State.RUNNING, coordinator.state());
    }
}
