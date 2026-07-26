package ani.rss.ownership;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
