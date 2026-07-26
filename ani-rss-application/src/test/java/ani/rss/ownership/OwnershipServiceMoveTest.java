package ani.rss.ownership;

import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipServiceMoveTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private OwnershipService service;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
        service = new OwnershipService(repository);
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void rollsBackFilesAndDatabaseRootsWhenAnyTargetConflicts() throws Exception {
        Path firstRoot = Files.createDirectories(tempDir.resolve("first"));
        Path secondRoot = Files.createDirectories(tempDir.resolve("second"));
        Path targetRoot = Files.createDirectories(tempDir.resolve("target"));
        Files.writeString(firstRoot.resolve("first.mkv"), "first");
        Files.writeString(secondRoot.resolve("second.mkv"), "second");
        Files.writeString(targetRoot.resolve("second.mkv"), "conflict");

        createOwnership("first", "hash-1", firstRoot, "first.mkv", 1L);
        createOwnership("second", "hash-2", secondRoot, "second.mkv", 2L);

        assertThrows(IllegalStateException.class,
                () -> service.moveSubscriptionFiles("subscription", targetRoot.toString()));

        assertTrue(Files.exists(firstRoot.resolve("first.mkv")));
        assertFalse(Files.exists(targetRoot.resolve("first.mkv")));
        assertEquals("conflict", Files.readString(targetRoot.resolve("second.mkv")));
        assertEquals(firstRoot.toAbsolutePath().normalize().toString(),
                repository.find("first").orElseThrow().saveRoot());
        assertEquals(secondRoot.toAbsolutePath().normalize().toString(),
                repository.find("second").orElseThrow().saveRoot());
    }

    private void createOwnership(String id, String hash, Path root, String file, long createdAt) {
        repository.createPending(new DownloadOwnership(
                id, "qBittorrent", id, hash, "subscription", 1, "1",
                root.toAbsolutePath().normalize().toString(), OwnershipState.ACTIVE,
                createdAt, createdAt));
        repository.replaceFiles(id, List.of(new OwnedFile(id, file, "FILE", null)));
    }
}
