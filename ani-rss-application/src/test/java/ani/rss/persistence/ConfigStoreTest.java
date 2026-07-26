package ani.rss.persistence;

import ani.rss.entity.Config;
import ani.rss.util.other.ConfigUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotIsDefensiveAndCommitIsDurable() throws Exception {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        ConfigStore store = new ConfigStore(runtime, () -> tempDir.resolve("config.json"), value -> { });

        Config snapshot = store.snapshot();
        snapshot.setDownloadToolHost("http://changed");
        assertNotSame(snapshot, runtime);
        assertEquals(ConfigUtil.CONFIG.getDownloadToolHost(), runtime.getDownloadToolHost());

        Config candidate = ConfigUtil.copy(runtime).setDownloadToolHost("http://new-host");
        store.commit(candidate);
        assertEquals("http://new-host", runtime.getDownloadToolHost());
        assertEquals("http://new-host", Files.readString(tempDir.resolve("config.json"))
                .contains("http://new-host") ? "http://new-host" : "");
    }

    @Test
    void failedCommitRestoresRuntimeAndLeavesDiskUntouched() {
        Config runtime = ConfigUtil.copy(ConfigUtil.CONFIG);
        Path target = tempDir.resolve("config.json");
        ConfigStore store = new ConfigStore(runtime, () -> target, value -> { },
                (path, content) -> {
                    throw new IOException("injected write failure");
                });

        Config original = ConfigUtil.copy(runtime);
        runtime.setDownloadToolHost("http://uncommitted");
        assertThrows(IllegalStateException.class, store::commitRuntimeCandidate);
        assertEquals(original.getDownloadToolHost(), runtime.getDownloadToolHost());
        assertEquals(original.getDownloadToolType(), runtime.getDownloadToolType());
        assertEquals(false, Files.exists(target));
    }
}
