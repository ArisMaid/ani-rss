package ani.rss.ownership;

import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void refusesFilesReachedThroughEscapingSymbolicLink() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("downloads"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path outsideFile = outside.resolve("episode.mkv");
        Files.writeString(outsideFile, "outside");
        try {
            Files.createSymbolicLink(root.resolve("linked"), outside);
        } catch (UnsupportedOperationException | SecurityException | java.io.IOException e) {
            Assumptions.abort("symbolic links are unavailable in this test environment: " + e.getMessage());
        }

        String ownershipId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                ownershipId, "qBittorrent", "remote", "symbolic-hash", "subscription",
                1, "1.0", root.toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles(ownershipId,
                List.of(new OwnedFile(ownershipId, "linked/episode.mkv", "FILE", 7L)));

        assertThrows(IllegalStateException.class,
                () -> quarantineService.quarantineOwnership(ownershipId));
        assertTrue(Files.exists(outsideFile));
        assertEquals("outside", Files.readString(outsideFile));
    }

    @Test
    void planDoesNotMoveFilesAndRejectsChangesBeforeExecution() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("planned"));
        Path file = root.resolve("episode.mkv");
        Files.writeString(file, "original");
        createOwnership("planned-owner", "planned-hash", "subscription", root,
                "episode.mkv", OwnershipState.ACTIVE);

        QuarantineService.DestructiveOperationPlan plan =
                quarantineService.planOwnership("planned-owner");

        assertTrue(Files.exists(file));
        assertEquals(1, plan.files().size());
        Files.writeString(file, "changed-after-confirmation");

        assertThrows(IllegalStateException.class,
                () -> quarantineService.executePlan(plan.operationId()));
        assertTrue(Files.exists(file));
        assertEquals("changed-after-confirmation", Files.readString(file));
        assertEquals(OwnershipState.ACTIVE,
                repository.find("planned-owner").orElseThrow().state());
    }

    @Test
    void conflictingOwnershipLeavesSharedFileUntouched() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("shared"));
        Path file = root.resolve("shared.mkv");
        Files.writeString(file, "shared");
        createOwnership("owner-a", "hash-a", "subscription-a", root,
                "shared.mkv", OwnershipState.ACTIVE);
        createOwnership("owner-b", "hash-b", "subscription-b", root,
                "shared.mkv", OwnershipState.ACTIVE);

        assertThrows(IllegalStateException.class,
                () -> quarantineService.planOwnership("owner-a"));

        assertTrue(Files.exists(file));
        assertEquals("shared", Files.readString(file));
        assertEquals(OwnershipState.ACTIVE, repository.find("owner-a").orElseThrow().state());
        assertEquals(OwnershipState.ACTIVE, repository.find("owner-b").orElseThrow().state());
    }

    @Test
    void quarantinesAndRestoresMultipleOwnershipsAtomically() throws Exception {
        Path firstRoot = Files.createDirectories(tempDir.resolve("multi-first"));
        Path secondRoot = Files.createDirectories(tempDir.resolve("multi-second"));
        Path first = firstRoot.resolve("first.mkv");
        Path second = secondRoot.resolve("second.mkv");
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        createOwnership("multi-a", "multi-hash-a", "subscription", firstRoot,
                "first.mkv", OwnershipState.ACTIVE);
        createOwnership("multi-b", "multi-hash-b", "subscription", secondRoot,
                "second.mkv", OwnershipState.LEGACY_ADOPTED);

        QuarantineService.DestructiveOperationPlan plan = quarantineService.planOwnerships(
                List.of("multi-a", "multi-b"));
        quarantineService.executePlan(plan.operationId());

        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
        assertEquals(OwnershipState.QUARANTINED, repository.find("multi-a").orElseThrow().state());
        assertEquals(OwnershipState.QUARANTINED, repository.find("multi-b").orElseThrow().state());

        quarantineService.restore(plan.operationId());

        assertTrue(Files.exists(first));
        assertTrue(Files.exists(second));
        assertEquals(OwnershipState.ACTIVE, repository.find("multi-a").orElseThrow().state());
        assertEquals(OwnershipState.LEGACY_ADOPTED, repository.find("multi-b").orElseThrow().state());
    }

    private void createOwnership(String ownershipId, String hash, String subscriptionId,
                                 Path root, String relativePath, OwnershipState state) {
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                ownershipId, "qBittorrent", ownershipId, hash, subscriptionId,
                1, "1.0", root.toString(), state, now, now));
        repository.replaceFiles(ownershipId, List.of(new OwnedFile(
                ownershipId, relativePath, "FILE", null)));
    }
}
