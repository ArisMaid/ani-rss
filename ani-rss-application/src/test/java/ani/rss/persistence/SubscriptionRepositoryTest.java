package ani.rss.persistence;

import ani.rss.entity.Ani;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubscriptionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void commitCopiesRecordsAndRejectsDuplicateIds() throws Exception {
        CopyOnWriteArrayList<Ani> runtime = new CopyOnWriteArrayList<>();
        SubscriptionRepository repository = new SubscriptionRepository(runtime,
                () -> tempDir.resolve("ani.json"));
        Ani ani = new Ani().setId("one").setTitle("Title").setUrl("https://example.test/rss");
        repository.commit(java.util.List.of(ani));

        ani.setTitle("mutated");
        assertEquals("Title", repository.snapshot().get(0).getTitle());
        assertThrows(IllegalArgumentException.class, () -> repository.commit(java.util.List.of(
                new Ani().setId("same"), new Ani().setId("same"))));
    }

    @Test
    void failedWriteRestoresPreviousSnapshot() {
        CopyOnWriteArrayList<Ani> runtime = new CopyOnWriteArrayList<>();
        Ani original = new Ani().setId("one").setTitle("old");
        runtime.add(original);
        SubscriptionRepository repository = new SubscriptionRepository(runtime,
                () -> tempDir.resolve("ani.json"),
                (path, content) -> {
                    throw new IOException("injected write failure");
                });
        repository.markCommitted(runtime);
        runtime.get(0).setTitle("new");

        assertThrows(IllegalStateException.class, repository::commitRuntimeCandidate);
        assertEquals("old", runtime.get(0).getTitle());
    }
}
