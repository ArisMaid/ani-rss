package ani.rss.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PathPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void preservesFileSystemRoots() {
        assertEquals("/", PathPolicy.normalize("/"));
        assertEquals("C:/", PathPolicy.normalize("C:/"));
    }

    @Test
    void resolvesOnlyWithinRoot() {
        assertEquals(tempDir.resolve("a/b").normalize().toAbsolutePath(),
                PathPolicy.resolveWithin(tempDir, "a/b"));
        assertThrows(IllegalArgumentException.class,
                () -> PathPolicy.resolveWithin(tempDir, "../outside"));
    }

    @Test
    void refusesRootDeletion() {
        assertThrows(IllegalArgumentException.class,
                () -> PathPolicy.requireSafeDeletionTarget(tempDir, tempDir));
    }
}
