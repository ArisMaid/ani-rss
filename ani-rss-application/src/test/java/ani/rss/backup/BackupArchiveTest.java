package ani.rss.backup;

import ani.rss.commons.GsonStatic;
import cn.hutool.core.util.ZipUtil;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
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
    void generatedArchiveKeepsUpstreamConfigurationFilesReadableByLegacyImporter() throws Exception {
        Path source = tempDir.resolve("fork-source");
        Files.createDirectories(source);
        String config = "{\"version\":\"3.1.75\",\"login\":{\"username\":\"legacy\"}}";
        String subscriptions = "[{\"id\":\"subscription\",\"title\":\"Legacy subscription\"}]";
        Files.writeString(source.resolve("config.v2.json"), config, StandardCharsets.UTF_8);
        Files.writeString(source.resolve("ani.v2.json"), subscriptions, StandardCharsets.UTF_8);
        Files.writeString(source.resolve("auth-state.v2.json"), "{\"schemaVersion\":2}", StandardCharsets.UTF_8);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        BackupArchive.create(bytes, source, "3.1.75.19");
        Path archive = tempDir.resolve("fork-backup.zip");
        Files.write(archive, bytes.toByteArray());

        // This is the unzip step used by the v3.1.75 importer. It ignores the
        // fork-only sidecar and manifest while preserving upstream files.
        Path legacyTarget = tempDir.resolve("legacy-target");
        ZipUtil.unzip(archive.toFile(), legacyTarget.toFile());

        assertEquals(config, Files.readString(legacyTarget.resolve("config.v2.json")));
        assertEquals(subscriptions, Files.readString(legacyTarget.resolve("ani.v2.json")));
        assertTrue(Files.exists(legacyTarget.resolve("auth-state.v2.json")));
        assertTrue(Files.exists(legacyTarget.resolve("manifest.json")));
        assertEquals("3.1.75", JsonParser.parseString(
                Files.readString(legacyTarget.resolve("config.v2.json")))
                .getAsJsonObject().get("version").getAsString());
        assertEquals("Legacy subscription", JsonParser.parseString(
                Files.readString(legacyTarget.resolve("ani.v2.json")))
                .getAsJsonArray().get(0).getAsJsonObject().get("title").getAsString());
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
        try (FileChannel channel = FileChannel.open(archive,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.position(BackupArchive.MAX_COMPRESSED_BYTES);
            channel.write(ByteBuffer.wrap(new byte[]{0}));
        }

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("large-out")));
    }

    @Test
    void rejectsZipWithTruncatedCentralDirectory() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "config.v2.json", "{}");
            put(zip, "ani.v2.json", "[]");
        }
        byte[] valid = bytes.toByteArray();
        int centralDirectory = indexOf(valid, new byte[]{'P', 'K', 1, 2});
        assertTrue(centralDirectory > 0);
        Path archive = tempDir.resolve("truncated.zip");
        Files.write(archive, Arrays.copyOf(valid, centralDirectory));

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("truncated-out")));
    }

    @Test
    void rejectsUnixSpecialFileEntry() throws Exception {
        Path archive = tempDir.resolve("special.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(output)) {
            put(zip, "config.v2.json", "{}");
            put(zip, "ani.v2.json", "[]");
            ZipArchiveEntry fifo = new ZipArchiveEntry("files/fifo");
            fifo.setUnixMode(0010000 | 0644);
            zip.putArchiveEntry(fifo);
            zip.closeArchiveEntry();
        }

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("special-out")));
    }

    @Test
    void rejectsManifestWithInvalidVersionOrCreationTime() throws Exception {
        List<BackupManifest> invalid = List.of(
                manifest("", System.currentTimeMillis(), sha256("{}")),
                manifest("3.1.75", 0, sha256("{}")));

        for (int i = 0; i < invalid.size(); i++) {
            Path archive = manifestArchive("invalid-manifest-" + i + ".zip", invalid.get(i), null);
            Path destination = tempDir.resolve("invalid-manifest-out-" + i);
            assertThrows(IOException.class,
                    () -> BackupArchive.validateAndExtract(archive, destination));
        }
    }

    @Test
    void rejectsManifestHashMismatch() throws Exception {
        BackupManifest manifest = manifest(
                "3.1.75", System.currentTimeMillis(), "0".repeat(64));
        Path archive = manifestArchive("hash-mismatch.zip", manifest, null);

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("hash-mismatch-out")));
    }

    @Test
    void rejectsCorruptSqliteDatabase() throws Exception {
        Path archive = tempDir.resolve("corrupt-database.zip");
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "config.v2.json", "{}");
            put(zip, "ani.v2.json", "[]");
            put(zip, "database.db", "not a sqlite database");
        }

        assertThrows(IOException.class,
                () -> BackupArchive.validateAndExtract(archive, tempDir.resolve("corrupt-database-out")));
    }

    private Path manifestArchive(String name, BackupManifest manifest, String database) throws IOException {
        Path archive = tempDir.resolve(name);
        try (OutputStream output = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            put(zip, "config.v2.json", "{}");
            put(zip, "ani.v2.json", "[]");
            if (database != null) {
                put(zip, "database.db", database);
            }
            put(zip, "manifest.json", GsonStatic.toJson(manifest));
        }
        return archive;
    }

    private static BackupManifest manifest(String version, long createdAt, String configHash)
            throws Exception {
        return new BackupManifest(
                BackupManifest.CURRENT_FORMAT,
                version,
                createdAt,
                List.of(
                        new BackupManifest.Entry("config.v2.json", 2, configHash),
                        new BackupManifest.Entry("ani.v2.json", 2, sha256("[]"))));
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static int indexOf(byte[] value, byte[] pattern) {
        for (int i = 0; i <= value.length - pattern.length; i++) {
            boolean matches = true;
            for (int j = 0; j < pattern.length; j++) {
                if (value[i + j] != pattern[j]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return i;
            }
        }
        return -1;
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void put(ZipArchiveOutputStream zip, String name, String content) throws IOException {
        zip.putArchiveEntry(new ZipArchiveEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeArchiveEntry();
    }
}
