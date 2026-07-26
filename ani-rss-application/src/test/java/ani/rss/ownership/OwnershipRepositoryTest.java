package ani.rss.ownership;

import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipRepositoryTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void reassignsDeletedOwnershipToTheNewSubscriptionAndArchivesThePriorState() {
        long before = System.currentTimeMillis() - 1_000;
        DownloadOwnership original = repository.createPending(ownership(
                "old-ownership", "old-subscription", "same-hash", OwnershipState.ACTIVE, before));
        repository.replaceFiles(original.ownershipId(), List.of(
                new OwnedFile(original.ownershipId(), "episode.mkv", "FILE", 42L)));
        repository.updateState(original.ownershipId(), OwnershipState.DELETED);

        long replacementCreatedAt = System.currentTimeMillis();
        DownloadOwnership replacement = repository.createPending(ownership(
                "new-ownership", "new-subscription", "same-hash", OwnershipState.PENDING,
                replacementCreatedAt));

        assertEquals(original.ownershipId(), replacement.ownershipId());
        assertEquals("new-subscription", replacement.subscriptionId());
        assertEquals(OwnershipState.PENDING, replacement.state());
        assertEquals(null, replacement.remoteTaskId());
        assertTrue(repository.listFiles(replacement.ownershipId()).isEmpty());
        assertEquals(1, reassignmentHistoryCount(original.ownershipId(), "old-subscription", "new-subscription"));
        assertEquals(1, reassignmentFileHistoryCount(original.ownershipId()));
    }

    @Test
    void neverReassignsAnOwnershipThatIsStillLiveOrAwaitingSafeResolution() {
        for (OwnershipState state : List.of(
                OwnershipState.ACTIVE,
                OwnershipState.LEGACY_ADOPTED,
                OwnershipState.PENDING,
                OwnershipState.FAILED,
                OwnershipState.QUARANTINED)) {
            String hash = "hash-" + state.name();
            repository.createPending(ownership("old-" + state.name(), "old-subscription", hash, state,
                    System.currentTimeMillis()));

            assertThrows(IllegalStateException.class, () -> repository.createPending(
                    ownership("new-" + state.name(), "new-subscription", hash, OwnershipState.PENDING,
                            System.currentTimeMillis())));
        }
    }

    private DownloadOwnership ownership(
            String ownershipId,
            String subscriptionId,
            String infoHash,
            OwnershipState state,
            long now) {
        return new DownloadOwnership(
                ownershipId,
                "qBittorrent",
                state == OwnershipState.ACTIVE ? "old-remote-task" : null,
                infoHash,
                subscriptionId,
                1,
                "1.0",
                tempDir.resolve("downloads").toString(),
                state,
                now,
                now);
    }

    private int reassignmentHistoryCount(String ownershipId, String oldSubscriptionId, String newSubscriptionId) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM ownership_reassignment_history
                    WHERE ownership_id = ? AND subscription_id = ? AND replacement_subscription_id = ?
                    """)) {
                statement.setString(1, ownershipId);
                statement.setString(2, oldSubscriptionId);
                statement.setString(3, newSubscriptionId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt(1) : 0;
                }
            }
        });
    }

    private int reassignmentFileHistoryCount(String ownershipId) {
        return DatabaseManager.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT COUNT(*) FROM ownership_reassignment_file_history files
                    JOIN ownership_reassignment_history history ON history.history_id = files.history_id
                    WHERE history.ownership_id = ?
                    """)) {
                statement.setString(1, ownershipId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt(1) : 0;
                }
            }
        });
    }
}
