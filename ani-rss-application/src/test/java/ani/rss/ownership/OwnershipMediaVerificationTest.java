package ani.rss.ownership;

import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipMediaVerificationTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private OwnershipService service;
    private DownloadOwnership ownership;
    private Path media;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
        service = new OwnershipService(repository);
        Path root = Files.createDirectories(tempDir.resolve("downloads"));
        media = root.resolve("episode.mkv");
        Files.writeString(media, "episode");
        long now = System.currentTimeMillis();
        ownership = repository.createPending(new DownloadOwnership(
                "owned", "qBittorrent", "task", "hash", "subscription", 1, "1",
                root.toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles("owned", List.of(new OwnedFile("owned", "episode.mkv", "FILE", 7L)));
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void reportsDeletedOrChangedOwnedMediaWithoutScanningTheDirectory() throws Exception {
        assertTrue(service.verifyMediaFiles(ownership).healthy());
        Files.writeString(media, "changed-content");
        assertFalse(service.verifyMediaFiles(ownership).healthy());
        Files.delete(media);
        assertFalse(service.verifyMediaFiles(ownership).healthy());
    }

    @Test
    void refusesToTreatAnAbsentManifestAsHealthyOrMissing() {
        repository.replaceFiles("owned", List.of());
        OwnershipService.MediaVerification verification = service.verifyMediaFiles(ownership);
        assertFalse(verification.manifestAvailable());
        assertFalse(verification.healthy());
    }

    @Test
    void confirmsOnlyExactOwnedFilesAtTheExpectedMigratedRoot() throws Exception {
        Path target = Files.createDirectories(tempDir.resolve("completed"));
        assertFalse(service.isSubscriptionAtRoot("subscription", target.toString()));

        Files.move(media, target.resolve("episode.mkv"));
        repository.updateSaveRoot("owned", target.toString());

        assertTrue(service.isSubscriptionAtRoot("subscription", target.toString()));
    }

    @Test
    void capturesTheFinalDownloaderPathsBeforeAutomaticTaskDeletion() throws Exception {
        Path renamed = media.resolveSibling("Example S01E01.mkv");
        Files.move(media, renamed);
        TorrentsInfo task = new TorrentsInfo()
                .setFilesSupplier(() -> List.of(renamed.getFileName().toString()));

        assertTrue(service.captureAndVerifyFiles("owned", task));
        assertEquals(List.of("Example S01E01.mkv"), repository.listFiles("owned").stream()
                .map(OwnedFile::relativePath)
                .toList());
    }

    @Test
    void blocksAutomaticTaskDeletionWhenTheFinalPathIsNotVisibleLocally() {
        TorrentsInfo task = new TorrentsInfo()
                .setFilesSupplier(() -> List.of("missing S01E01.mkv"));

        assertFalse(service.captureAndVerifyFiles("owned", task));
    }
}
