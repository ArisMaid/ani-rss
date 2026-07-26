package ani.rss.commons;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AtomicFileWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyReplacesUtf8Content() throws Exception {
        Path target = tempDir.resolve("config.json");
        AtomicFileWriter.writeUtf8(target, "旧值");
        AtomicFileWriter.writeUtf8(target, "新值");
        assertEquals("新值", Files.readString(target));
    }
}
