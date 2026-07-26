package ani.rss.backup;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupArchiveTest {
    @TempDir
    Path tempDir;

    @Test
    void createsAndValidatesManifestBackedArchive() throws Exception {
        Path source = tempDir.resolve("source");
        Files.createDirectories(source.resolve("files"));
        Files.writeString(source.resolve("config.v2.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("ani.v2.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(source.resolve("files/episode.mkv"), "media", StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BackupArchive.create(bytes, source, "3.1.75");
        Path archive = tempDir.resolve("backup.zip");
        Files.write(archive, bytes.toByteArray());

        Path extracted = tempDir.resolve("extracted");
        BackupValidation validation = BackupArchive.validateAndExtract(archive, extracted);

        assertTrue(validation.valid());
        assertFalse(validation.legacy());
        assertEquals("3.1.75", validation.applicationVersion());
        assertEquals("media", Files.readString(extracted.resolve("files/episode.mkv")));
    }

    @Test
    void acceptsLegacyArchiveWithValidationWarning() throws Exception {
        Path archive = tempDir.resolve("legacy.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "config.v2.json", "{}");
            put(zip, "ani.v2.json", "[]");
        }

        BackupValidation validation = BackupArchive.validateAndExtract(archive, tempDir.resolve("legacy-out"));

        assertTrue(validation.valid());
        assertTrue(validation.legacy());
        assertFalse(validation.warnings().isEmpty());
    }

    @Test
    void rejectsTraversalAndUndeclaredEntries() throws Exception {
        Path archive = tempDir.resolve("malicious.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "../outside.txt", "bad");
        }

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("malicious-out")));
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void rejectsTooLargeCompressedArchiveBeforeExtraction() throws Exception {
        Path archive = tempDir.resolve("large.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("config.v2.json");
            zip.putArchiveEntry(entry);
            byte[] chunk = new byte[1024 * 1024];
            for (int i = 0; i < 51; i++) {
                zip.write(chunk);
            }
            zip.closeArchiveEntry();
        }

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("large-out")));
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
