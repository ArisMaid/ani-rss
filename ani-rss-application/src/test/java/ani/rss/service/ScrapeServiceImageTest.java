package ani.rss.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScrapeServiceImageTest {
    @TempDir
    Path tempDir;

    @Test
    void failedWriteKeepsExistingImage() throws Exception {
        Path target = tempDir.resolve("poster.jpg");
        Files.writeString(target, "existing", StandardCharsets.UTF_8);

        InputStream failing = new InputStream() {
            private int remaining = 3;

            @Override
            public int read() throws IOException {
                if (remaining-- > 0) {
                    return 'x';
                }
                throw new IOException("simulated network failure");
            }
        };

        assertThrows(IOException.class,
                () -> ScrapeService.writeImageAtomically(target, failing, true));
        assertEquals("existing", Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void completeWriteAtomicallyReplacesExistingImage() throws Exception {
        Path target = tempDir.resolve("poster.jpg");
        Files.writeString(target, "existing", StandardCharsets.UTF_8);

        ScrapeService.writeImageAtomically(target,
                new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8)), true);

        assertEquals("replacement", Files.readString(target, StandardCharsets.UTF_8));
    }
}
