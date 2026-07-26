package ani.rss.ownership;

import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipMigrationServiceTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path tempDir;

    @Test
    void cachedMagnetMustMatchItsFilenameHash() throws Exception {
        Path cached = tempDir.resolve(HASH + ".txt");
        Files.writeString(cached,
                "magnet:?xt=urn:btih:ffffffffffffffffffffffffffffffffffffffff");

        assertFalse(OwnershipMigrationService.cachedTorrentMatches(cached.toFile(), HASH));

        Files.writeString(cached, "magnet:?dn=episode&xt=urn:btih:" + HASH);
        assertTrue(OwnershipMigrationService.cachedTorrentMatches(cached.toFile(), HASH));
    }

    @Test
    void cachedTorrentUsesItsCanonicalInfoHashInsteadOfOpaqueCacheFilename() throws Exception {
        Path cached = tempDir.resolve("rss-enclosure-key.torrent");
        Files.write(cached, torrentBytes());

        String hash = TorrentUtil.getInfoHash(cached.toFile());

        assertNotEquals("rss-enclosure-key", hash);
        assertEquals(hash, TorrentUtil.getInfoHash(cached.toFile()));
        assertTrue(OwnershipMigrationService.cachedTorrentMatches(cached.toFile(), hash));
    }

    @Test
    void emptyCachedTorrentIsNotParsedOrAdopted() throws Exception {
        Path cached = tempDir.resolve("empty.torrent");
        Files.write(cached, new byte[0]);

        assertFalse(OwnershipMigrationService.cachedTorrentMatches(cached.toFile(), HASH));
    }

    private static byte[] torrentBytes() throws Exception {
        ByteArrayOutputStream torrent = new ByteArrayOutputStream();
        torrent.write('d');
        bytes(torrent, "announce");
        bytes(torrent, "");
        bytes(torrent, "info");
        torrent.write('d');
        bytes(torrent, "length");
        integer(torrent, 1);
        bytes(torrent, "name");
        bytes(torrent, "test.mkv");
        bytes(torrent, "piece length");
        integer(torrent, 16 * 1024);
        bytes(torrent, "pieces");
        bytes(torrent, MessageDigest.getInstance("SHA-1").digest(new byte[]{'x'}));
        bytes(torrent, "source");
        bytes(torrent, "ani-rss-test");
        torrent.write('e');
        torrent.write('e');
        return torrent.toByteArray();
    }

    private static void integer(ByteArrayOutputStream output, int value) {
        output.writeBytes(("i" + value + "e").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void bytes(ByteArrayOutputStream output, String value) {
        bytes(output, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void bytes(ByteArrayOutputStream output, byte[] value) {
        output.writeBytes((value.length + ":").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.writeBytes(value);
    }
}
