package ani.rss.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import wushuo.tmdb.api.entity.TmdbImage;

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

    @Test
    void selectsFourDistinctLargeAdditionalBackdropsWithoutThePrimaryImage() {
        TmdbImage primary = image("/primary.jpg", 1920);
        TmdbImage small = image("/small.jpg", 1279);
        TmdbImage missingWidth = image("/unknown.jpg", null);
        TmdbImage first = image("/first.jpg", 1280);
        TmdbImage duplicate = image("/first.jpg", 3840);
        TmdbImage second = image("/second.png", 1920);
        TmdbImage third = image("/third.webp", 1920);
        TmdbImage fourth = image("/fourth.jpg", 1920);
        TmdbImage fifth = image("/fifth.jpg", 1920);

        List<TmdbImage> selected = ScrapeService.selectAdditionalBackdrops(
                primary.getFilePath(),
                List.of(primary, small, missingWidth, first, duplicate, second, third, fourth, fifth));

        assertEquals(List.of("/first.jpg", "/second.png", "/third.webp", "/fourth.jpg"),
                selected.stream().map(TmdbImage::getFilePath).toList());
    }

    private static TmdbImage image(String path, Integer width) {
        return new TmdbImage().setFilePath(path).setWidth(width);
    }
}
