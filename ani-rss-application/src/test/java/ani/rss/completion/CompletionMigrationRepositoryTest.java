package ani.rss.completion;

import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionMigrationRepositoryTest {
    @TempDir
    Path tempDir;

    private CompletionMigrationRepository repository;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new CompletionMigrationRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void retainsPreparedRecordsForCrashRecoveryButNotFailedRecords() {
        repository.prepare("prepared", "fingerprint-a", "/target-a");
        repository.prepare("moved", "fingerprint-b", "/target-b");
        repository.setState("moved", CompletionMigrationState.MOVED);
        repository.prepare("failed", "fingerprint-c", "/target-c");
        repository.setState("failed", CompletionMigrationState.FAILED);

        assertEquals(2, repository.listPendingFinalization().size());
        assertTrue(repository.listPendingFinalization().stream()
                .anyMatch(record -> "prepared".equals(record.subscriptionId()) &&
                        record.state() == CompletionMigrationState.PREPARED));
        assertTrue(repository.listPendingFinalization().stream()
                .anyMatch(record -> "moved".equals(record.subscriptionId()) &&
                        record.state() == CompletionMigrationState.MOVED));
    }
}
