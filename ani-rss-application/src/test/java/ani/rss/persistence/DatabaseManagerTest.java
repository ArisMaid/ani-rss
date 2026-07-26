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
}
