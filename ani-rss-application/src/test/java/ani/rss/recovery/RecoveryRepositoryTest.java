package ani.rss.recovery;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.ownership.OwnershipRepository;
import ani.rss.ownership.OwnershipService;
import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RecoveryRepositoryTest {
    @TempDir
    Path tempDir;

    private RecoveryRepository repository;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new RecoveryRepository();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void preservesAcceptedItemForRecoveryAfterTheRssWindowExpires() {
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item item = new Item().setInfoHash("ABC123").setEpisode(2.0)
                .setTorrent("https://example.invalid/item.torrent").setReName("Example S01E02");

        RecoveryRecord observed = repository.observe(ani, item);
        repository.scheduleRetry(ani.getId(), item.getInfoHash(), "DOWNLOAD_REJECTED", 0L);

        RecoveryRecord retry = repository.listRecoverable(ani.getId()).get(0);
        assertEquals(observed.recoveryId(), retry.recoveryId());
        assertEquals("abc123", retry.infoHash());
        assertEquals(RecoveryState.RETRY_WAIT, retry.state());
        assertEquals("DOWNLOAD_REJECTED", retry.lastErrorCode());
        assertTrue(retry.itemJson().contains("Example S01E02"));
    }

    @Test
    void cancellingOneRecordDoesNotCancelOtherEpisodes() {
        Ani ani = new Ani().setId("subscription").setSeason(1);
        repository.observe(ani, new Item().setInfoHash("first").setTorrent("magnet:?xt=urn:btih:first"));
        repository.observe(ani, new Item().setInfoHash("second").setTorrent("magnet:?xt=urn:btih:second"));

        repository.cancel(ani.getId(), "first");

        assertEquals(1, repository.listRecoverable(ani.getId()).size());
        assertEquals("second", repository.listRecoverable(ani.getId()).get(0).infoHash());
    }

    @Test
    void retainsDelayedEligibleItemUntilItsNormalDeadline() {
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item item = new Item().setInfoHash("delayed").setTorrent("magnet:?xt=urn:btih:delayed");

        repository.observe(ani, item);
        long deadline = System.currentTimeMillis() + 60_000L;
        repository.defer(ani.getId(), item.getInfoHash(), deadline);

        RecoveryRecord deferred = repository.find(ani.getId(), item.getInfoHash()).orElseThrow();
        assertEquals(RecoveryState.PENDING, deferred.state());
        assertEquals(deadline, deferred.nextAttemptAt());
    }

    @Test
    void promotesOpaqueRssHashWithoutLosingTheCachedInputIdentity() {
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item item = new Item().setInfoHash("episode-file-name")
                .setTorrent("https://example.invalid/episode.torrent");

        repository.observe(ani, item);
        RecoveryRecord promoted = repository.promoteCanonicalHash(
                ani.getId(), item.getInfoHash(), "AABBCCDDEEFF").orElseThrow();

        assertEquals("episode-file-name", promoted.sourceHash());
        assertEquals("aabbccddeeff", promoted.infoHash());
        assertEquals(promoted.recoveryId(), repository.observe(ani, item).recoveryId());
        assertEquals(promoted.recoveryId(), repository.find(ani.getId(), "episode-file-name").orElseThrow().recoveryId());
        assertEquals(promoted.recoveryId(), repository.find(ani.getId(), "aabbccddeeff").orElseThrow().recoveryId());
        assertEquals(1, repository.listRecoverable(ani.getId()).size());
    }

    @Test
    void refusesToAliasTwoDifferentRssCacheInputsToOneCanonicalHash() {
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item first = new Item().setInfoHash("episode-one").setTorrent("https://example.invalid/one.torrent");
        Item second = new Item().setInfoHash("episode-two").setTorrent("https://example.invalid/two.torrent");

        repository.observe(ani, first);
        repository.observe(ani, second);
        repository.promoteCanonicalHash(ani.getId(), first.getInfoHash(), "same-canonical").orElseThrow();

        assertTrue(repository.promoteCanonicalHash(ani.getId(), second.getInfoHash(), "same-canonical").isEmpty());
        assertEquals("same-canonical", repository.find(ani.getId(), first.getInfoHash()).orElseThrow().infoHash());
        assertEquals("episode-two", repository.find(ani.getId(), second.getInfoHash()).orElseThrow().infoHash());
    }

    @Test
    void delegatesPreviouslyTrackedItemsToRecoveryInsteadOfSubmittingAgain() {
        MissingEpisodeRecoveryService service = recoveryService();
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item item = new Item().setInfoHash("tracked").setTorrent("magnet:?xt=urn:btih:tracked");

        assertEquals(MissingEpisodeRecoveryService.SubmissionDisposition.NEW,
                service.prepareEligible(ani, item, false));
        assertEquals(MissingEpisodeRecoveryService.SubmissionDisposition.TRACKED,
                service.prepareEligible(ani, item, false));
    }

    @Test
    void treatsAnUntrackedLegacyTorrentCacheAsAlreadySatisfied() {
        MissingEpisodeRecoveryService service = recoveryService();
        Ani ani = new Ani().setId("subscription").setSeason(1);
        Item item = new Item().setInfoHash("legacy-cache")
                .setTorrent("https://example.invalid/legacy-cache.torrent");

        assertEquals(MissingEpisodeRecoveryService.SubmissionDisposition.LEGACY_CACHE_SATISFIED,
                service.prepareEligible(ani, item, true));
        assertEquals(RecoveryState.SATISFIED,
                repository.find(ani.getId(), item.getInfoHash()).orElseThrow().state());
        assertEquals(MissingEpisodeRecoveryService.SubmissionDisposition.TRACKED,
                service.prepareEligible(ani, item, true));
    }

    @SuppressWarnings("unchecked")
    private MissingEpisodeRecoveryService recoveryService() {
        return new MissingEpisodeRecoveryService(
                repository,
                new OwnershipService(new OwnershipRepository()),
                mock(org.springframework.beans.factory.ObjectProvider.class));
    }
}
