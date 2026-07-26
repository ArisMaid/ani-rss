package ani.rss.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilsMoveTest {
    @TempDir
    Path tempDir;

    @Test
    void refusesToOverwriteAnExistingTarget() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "source");
        Files.writeString(target, "target");

        assertThrows(IllegalStateException.class, () -> FileUtils.move(source, target));

        assertEquals("source", Files.readString(source));
        assertEquals("target", Files.readString(target));
    }

    @Test
    void movesToANewExactTarget() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "source");

        FileUtils.move(source, target);

        assertTrue(Files.notExists(source));
        assertEquals("source", Files.readString(target));
    }
}
