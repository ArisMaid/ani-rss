package ani.rss.ownership;

import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuarantineServiceTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private QuarantineService quarantineService;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
        OwnershipService ownershipService = new OwnershipService(repository);
        quarantineService = new QuarantineService(ownershipService, repository);
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void quarantinesAndRestoresOnlyManifestFiles() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("downloads"));
        Path owned = root.resolve("season/episode.mkv");
        Path unrelated = root.resolve("season/unrelated.mkv");
        Files.createDirectories(owned.getParent());
        Files.writeString(owned, "owned");
        Files.writeString(unrelated, "unrelated");

        String ownershipId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                ownershipId, "qBittorrent", "remote", "abcdef", "subscription",
                1, "1.0", root.toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles(ownershipId,
                List.of(new OwnedFile(ownershipId, "season/episode.mkv", "FILE", 5L)));

        String operationId = quarantineService.quarantineOwnership(ownershipId);
        assertFalse(Files.exists(owned));
        assertTrue(Files.exists(unrelated));
        assertEquals(OwnershipState.QUARANTINED, repository.find(ownershipId).orElseThrow().state());

        quarantineService.restore(operationId);
        assertTrue(Files.exists(owned));
        assertEquals("owned", Files.readString(owned));
        assertEquals(OwnershipState.ACTIVE, repository.find(ownershipId).orElseThrow().state());
    }
}
