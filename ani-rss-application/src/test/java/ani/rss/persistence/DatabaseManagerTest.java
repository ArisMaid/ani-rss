package ani.rss.persistence;

import ani.rss.util.basic.RenameCacheUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    Path tempDir;

    @BeforeEach
    void configureDatabase() {
        System.setProperty("CONFIG", tempDir.toString());
        DatabaseManager.close();
    }

    @AfterEach
    void closeDatabase() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void migratesAndReopensDatabase() {
        RenameCacheUtil.put("task", "name");
        assertEquals("name", RenameCacheUtil.get("task"));
        assertTrue(DatabaseManager.integrityCheck());

        DatabaseManager.reopen();
        assertEquals("name", RenameCacheUtil.get("task"));
        RenameCacheUtil.remove("task");
        assertNull(RenameCacheUtil.get("task"));
    }

    @Test
    void createsConsistentSnapshotWithQuotedFilename() {
        RenameCacheUtil.put("snapshot", "value");
        Path snapshot = tempDir.resolve("backup's copy.db");

        DatabaseManager.backupTo(snapshot);

        assertTrue(DatabaseManager.integrityCheck(snapshot));
    }

    @Test
    void installsAndUsesThePollingIndexes() {
        DatabaseManager.withConnection(connection -> {
            try (var statement = connection.createStatement();
                 var versions = statement.executeQuery("SELECT MAX(version) FROM schema_migrations")) {
                assertTrue(versions.next());
                assertTrue(versions.getInt(1) >= 9);
            }

            String ownershipPlan = queryPlan(connection, """
                    EXPLAIN QUERY PLAN
                    SELECT * FROM download_ownership
                    WHERE downloader_type = 'qBittorrent' AND remote_task_id = 'task-id'
                    """);
            assertTrue(ownershipPlan.contains("idx_download_ownership_remote"), ownershipPlan);

            String recoveryPlan = queryPlan(connection, """
                    EXPLAIN QUERY PLAN
                    SELECT * FROM missing_episode_recovery
                    WHERE subscription_id = 'subscription'
                      AND state = 'SATISFIED'
                      AND updated_at <= 1
                    """);
            assertTrue(recoveryPlan.contains("idx_missing_episode_recovery_audit"), recoveryPlan);
            return null;
        });
    }

    private static String queryPlan(java.sql.Connection connection, String sql) throws java.sql.SQLException {
        StringBuilder result = new StringBuilder();
        try (var statement = connection.createStatement();
             var rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.append(rows.getString("detail")).append('\n');
            }
        }
        return result.toString();
    }
}
