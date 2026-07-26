package ani.rss.update;

import ani.rss.entity.About;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseUpdateTest {
    @TempDir
    Path tempDir;

    @Test
    void writesOnlyAnExactlySizedDigestVerifiedUpdate() throws Exception {
        byte[] content = "verified update".getBytes(StandardCharsets.UTF_8);
        Path target = Files.createFile(tempDir.resolve("update.tmp"));

        BaseUpdate.copyVerified(new ByteArrayInputStream(content), target,
                content.length, sha256(content));

        assertArrayEquals(content, Files.readAllBytes(target));
    }

    @Test
    void rejectsOversizedTruncatedAndDigestMismatchedResponses() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        Path target = Files.createFile(tempDir.resolve("update.tmp"));

        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.copyVerified(
                new ByteArrayInputStream(content), target, content.length - 1, sha256(content)));
        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.copyVerified(
                new ByteArrayInputStream(content), target, content.length + 1, sha256(content)));
        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.copyVerified(
                new ByteArrayInputStream(content), target, content.length, "0".repeat(64)));
    }

    @Test
    void rejectsUnsafeOrUnboundedMetadata() {
        About valid = new About()
                .setDownloadUrl("https://github.com/example/release.bin")
                .setSize(10L)
                .setSha256("a".repeat(64));
        BaseUpdate.validateMetadata(valid);

        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.validateMetadata(
                new About().setDownloadUrl("http://example.com/release.bin")
                        .setSize(10L).setSha256("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.validateMetadata(
                new About().setDownloadUrl("https://user:secret@example.com/release.bin")
                        .setSize(10L).setSha256("a".repeat(64))));
        assertThrows(IllegalArgumentException.class, () -> BaseUpdate.validateMetadata(
                new About().setDownloadUrl("https://example.com/release.bin")
                        .setSize(BaseUpdate.MAX_UPDATE_BYTES + 1).setSha256("a".repeat(64))));
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
}
