package ani.rss.persistence;

import ani.rss.entity.Ani;
import ani.rss.commons.GsonStatic;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

    @Test
    void retainsUnknownFieldsFromANewerCompatibleSubscriptionDocument() throws Exception {
        Path target = tempDir.resolve("ani.json");
        JsonObject source = GsonStatic.GSON.toJsonTree(new Ani()
                .setId("one")
                .setTitle("old")
                .setUrl("https://example.test/rss")).getAsJsonObject();
        source.addProperty("futureSubscriptionField", "preserve-me");
        JsonArray document = new JsonArray();
        document.add(source);
        Files.writeString(target, GsonStatic.toJson(document));

        CopyOnWriteArrayList<Ani> runtime = new CopyOnWriteArrayList<>();
        SubscriptionRepository repository = new SubscriptionRepository(runtime, () -> target);
        java.util.List<Ani> loaded = repository.readFromDisk();
        loaded.get(0).setTitle("new");
        repository.commit(loaded);

        JsonObject saved = JsonParser.parseString(Files.readString(target)).getAsJsonArray()
                .get(0).getAsJsonObject();
        assertEquals("preserve-me", saved.get("futureSubscriptionField").getAsString());
        assertEquals("new", saved.get("title").getAsString());
    }

    @Test
    void retainsFutureFieldsWithTheCorrectSubscriptionAfterReordering() throws Exception {
        Path target = tempDir.resolve("ani.json");
        JsonObject first = GsonStatic.GSON.toJsonTree(new Ani()
                .setId("first").setTitle("First").setUrl("https://example.test/first")).getAsJsonObject();
        JsonObject second = GsonStatic.GSON.toJsonTree(new Ani()
                .setId("second").setTitle("Second").setUrl("https://example.test/second")).getAsJsonObject();
        first.addProperty("futureField", "first");
        second.addProperty("futureField", "second");
        JsonArray document = new JsonArray();
        document.add(first);
        document.add(second);
        Files.writeString(target, GsonStatic.toJson(document));

        CopyOnWriteArrayList<Ani> runtime = new CopyOnWriteArrayList<>();
        SubscriptionRepository repository = new SubscriptionRepository(runtime, () -> target);
        java.util.List<Ani> loaded = repository.readFromDisk();
        repository.commit(java.util.List.of(loaded.get(1), loaded.get(0)));

        JsonArray saved = JsonParser.parseString(Files.readString(target)).getAsJsonArray();
        assertEquals("second", saved.get(0).getAsJsonObject().get("futureField").getAsString());
        assertEquals("first", saved.get(1).getAsJsonObject().get("futureField").getAsString());
    }
}
