package ani.rss.util.other;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

class TorrentMetadataTest {
    @TempDir Path directory;

    private TorrentMetadata metadata(String info) throws Exception {
        Path path = directory.resolve("sample.torrent");
        Files.writeString(path, "d4:info" + info + "e", StandardCharsets.ISO_8859_1);
        return TorrentMetadata.from(path.toFile());
    }

    private String hash(String algorithm, String info) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm)
                .digest(info.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test void v1UsesExactInfoBytes() throws Exception {
        String info = "d6:lengthi12e4:name5:a.mkv6:pieces20:12345678901234567890e";
        TorrentMetadata result = metadata(info);
        assertEquals(hash("SHA-1", info), result.getHash());
        assertEquals("magnet:?xt=urn:btih:" + result.getHash(), result.getMagnetUri());
        assertArrayEquals(new String[]{"a.mkv"}, result.getFilenames());
        assertArrayEquals(new long[]{12}, result.getLengths());
    }

    @Test void pureV2UsesFileTreeAndTruncatedDownloaderIdentity() throws Exception {
        String info = "d9:file treed5:a.mkvd0:d6:lengthi12eeee12:meta versioni2e4:name4:teste";
        TorrentMetadata result = metadata(info);
        assertEquals(hash("SHA-256", info).substring(0, 40), result.getHash());
        assertEquals("magnet:?xt=urn:btmh:1220" + hash("SHA-256", info), result.getMagnetUri());
        assertArrayEquals(new String[]{"a.mkv"}, result.getFilenames());
        assertArrayEquals(new long[]{12}, result.getLengths());
    }

    @Test void hybridKeepsV1IdentityAndBothMagnetTopics() throws Exception {
        String info = "d6:lengthi12e12:meta versioni2e4:name5:a.mkv6:pieces20:12345678901234567890e";
        TorrentMetadata result = metadata(info);
        assertEquals(hash("SHA-1", info), result.getHash());
        assertTrue(result.getMagnetUri().contains("btmh:1220" + hash("SHA-256", info)));
        assertTrue(result.getMagnetUri().contains("btih:" + hash("SHA-1", info)));
    }
}
