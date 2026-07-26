package ani.rss.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClearServiceTest {
    @TempDir
    Path tempDir;

    private final ClearService clearService = new ClearService();

    @AfterEach
    void tearDown() {
        System.clearProperty("CONFIG");
    }

    @Test
    void doesNotTreatImagesMetadataOrPlatformFilesAsEmpty() throws Exception {
        Path directory = Files.createDirectories(tempDir.resolve("shared"));
        Files.writeString(directory.resolve("cover.jpg"), "cover");
        Files.writeString(directory.resolve("fanart1.jpg"), "fanart");
        Files.writeString(directory.resolve("metadata.nfo"), "metadata");
        Files.writeString(directory.resolve("bangumi.ini"), "bangumi");
        Files.writeString(directory.resolve(".DS_Store"), "platform");

        clearService.clearDir(directory.toFile(), true, true, 2);

        assertTrue(Files.exists(directory));
        assertEquals(5, Files.list(directory).count());
    }

    @Test
    void removesOnlyTrulyEmptyParentsWithinBound() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("root"));
        Files.writeString(root.resolve("keep.txt"), "keep");
        Path parent = Files.createDirectories(root.resolve("parent"));
        Path child = Files.createDirectories(parent.resolve("child"));

        clearService.clearDir(child.toFile(), true, true, 2);

        assertFalse(Files.exists(child));
        assertFalse(Files.exists(parent));
        assertTrue(Files.exists(root.resolve("keep.txt")));
    }

    @Test
    void previewCleanupSkipsNestedOrUnknownContent() throws Exception {
        Path config = Files.createDirectories(tempDir.resolve("config"));
        System.setProperty("CONFIG", config.toString());
        Path imageRoot = Files.createDirectories(config.resolve("img"));
        Files.writeString(imageRoot.resolve("cached.jpg"), "cache");
        Path unknown = Files.createDirectories(imageRoot.resolve("unknown"));
        Files.writeString(unknown.resolve("keep.jpg"), "keep");

        long deleted = clearService.clearPreviewImages();

        assertEquals(5L, deleted);
        assertFalse(Files.exists(imageRoot.resolve("cached.jpg")));
        assertTrue(Files.exists(unknown.resolve("keep.jpg")));
    }
}
