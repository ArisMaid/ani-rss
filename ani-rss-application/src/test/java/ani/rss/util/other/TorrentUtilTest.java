package ani.rss.util.other;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TorrentUtilTest {
    @TempDir
    Path tempDir;

    @Test
    void cachedBase32MagnetUsesTheSameHexIdentityAsTheDownloader() throws Exception {
        String hex = "0123456789abcdef0123456789abcdef01234567";
        String base32 = cn.hutool.core.codec.Base32.encode(java.util.HexFormat.of().parseHex(hex));
        Path cache = tempDir.resolve("opaque-rss-identifier.txt");
        Files.writeString(cache, "magnet:?xt=urn:btih:" + base32.toLowerCase() + "&dn=Example");
        assertEquals(hex, TorrentUtil.getCanonicalInfoHash(cache.toFile()));
        assertEquals(hex, TorrentUtil.getInfoHash(cache.toFile()));
        Files.writeString(cache, "magnet:?xt=urn:btih:" + hex.toUpperCase());
        assertEquals(hex, TorrentUtil.getCanonicalInfoHash(cache.toFile()));
        Files.writeString(cache, "magnet:?xt=urn:btih:not-a-valid-identity");
        assertEquals("", TorrentUtil.getCanonicalInfoHash(cache.toFile()));
    }

    @Test
    void getTorrentPrefersRegularTxtThenRegularTorrentBeforeLinkType() throws Exception {
        String originalConfigPath = System.getProperty("CONFIG");
        System.setProperty("CONFIG", tempDir.toString());
        try {
            Ani ani = new Ani().setTitle("Example").setSeason(1).setOva(false);

            Path torrentDir = tempDir.resolve("torrents").resolve("Example").resolve("Season 1");
            Files.createDirectories(torrentDir);

            Item torrentLink = new Item().setInfoHash("hash-txt").setTorrent("https://example.test/item.torrent");
            Path txt = torrentDir.resolve("hash-txt.txt");
            Path torrent = torrentDir.resolve("hash-txt.torrent");
            Files.writeString(txt, "magnet:?xt=urn:btih:hash-txt");
            Files.writeString(torrent, "torrent-bytes");
            assertEquals(txt.toFile(), TorrentUtil.getTorrent(ani, torrentLink));

            Item magnetLink = new Item().setInfoHash("hash-torrent").setTorrent("magnet:?xt=urn:btih:hash-torrent");
            Path torrentOnly = torrentDir.resolve("hash-torrent.torrent");
            Files.writeString(torrentOnly, "torrent-bytes");
            assertEquals(torrentOnly.toFile(), TorrentUtil.getTorrent(ani, magnetLink));

            Item missingMagnet = new Item().setInfoHash("hash-missing-txt").setTorrent("magnet:?xt=urn:btih:hash-missing-txt");
            assertEquals(torrentDir.resolve("hash-missing-txt.txt").toFile(), TorrentUtil.getTorrent(ani, missingMagnet));

            Item missingTorrent = new Item().setInfoHash("hash-missing-torrent").setTorrent("https://example.test/item.torrent");
            assertEquals(torrentDir.resolve("hash-missing-torrent.torrent").toFile(), TorrentUtil.getTorrent(ani, missingTorrent));
        } finally {
            if (originalConfigPath == null) {
                System.clearProperty("CONFIG");
            } else {
                System.setProperty("CONFIG", originalConfigPath);
            }
        }
    }
}
