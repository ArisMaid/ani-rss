package ani.rss.service;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionDirectoryCleanupPolicyTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesTheStaticDownloadRootForTheDefaultSubscriptionTemplate() {
        Path downloadBase = tempDir.resolve("downloads");
        Config config = new Config().setDownloadPathTemplate(
                downloadBase + "/${title}/Season ${season}");
        DownloadOwnership ownership = ownershipAt(downloadBase.resolve("Example").resolve("Season 1"));

        Optional<Path> boundary = SubscriptionDirectoryCleanupPolicy.resolveBoundary(
                new Ani().setTitle("Example").setSeason(1), ownership, config);

        assertTrue(boundary.isPresent());
        assertEquals(downloadBase.toAbsolutePath().normalize(), boundary.orElseThrow());
    }

    @Test
    void rejectsStaticOrStaleTemplatesThatCannotProveTheCleanupScope() {
        Path downloadBase = tempDir.resolve("downloads");
        DownloadOwnership ownership = ownershipAt(downloadBase.resolve("Example").resolve("Season 1"));

        Optional<Path> staticBoundary = SubscriptionDirectoryCleanupPolicy.resolveBoundary(
                new Ani().setTitle("Example").setSeason(1), ownership,
                new Config().setDownloadPathTemplate(downloadBase.resolve("shared").toString()));
        Optional<Path> staleBoundary = SubscriptionDirectoryCleanupPolicy.resolveBoundary(
                new Ani().setTitle("Example").setSeason(1), ownership,
                new Config().setDownloadPathTemplate(tempDir.resolve("other").toString() + "/${title}"));

        assertTrue(staticBoundary.isEmpty());
        assertTrue(staleBoundary.isEmpty());
    }

    private static DownloadOwnership ownershipAt(Path saveRoot) {
        long now = System.currentTimeMillis();
        return new DownloadOwnership("ownership", "qBittorrent", "task", "hash", "subscription",
                1, "1", saveRoot.toString(), OwnershipState.ACTIVE, now, now);
    }
}
