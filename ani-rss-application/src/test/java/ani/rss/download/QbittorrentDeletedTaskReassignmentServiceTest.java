package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipRepository;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.ownership.QbittorrentDeletedTaskReassignmentService;
import ani.rss.persistence.DatabaseManager;
import ani.rss.service.DownloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QbittorrentDeletedTaskReassignmentServiceTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private QbittorrentDeletedTaskReassignmentService service;
    private FakeClient fakeClient;
    private DownloadOwnership replacement;

    @BeforeEach
    void setUp() {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
        OwnershipService ownershipService = new OwnershipService(repository);
        service = new QbittorrentDeletedTaskReassignmentService(ownershipService, repository);
        fakeClient = new FakeClient();
        replacement = reassignDeletedOwnership();
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void reattachesOnlyTheExactDeletedAniRssTask() {
        TorrentsInfo task = oldOwnedTask("old-task", "same-hash", "/downloads", "ani-rss", "old-subscription");
        fakeClient.tasks.add(task);

        assertTrue(service.reattach(fakeClient.client(), replacement));
        assertEquals(List.of("new-subscription"), fakeClient.addedTags);
        DownloadOwnership activated = repository.find(replacement.ownershipId()).orElseThrow();
        assertEquals(OwnershipState.ACTIVE, activated.state());
        assertEquals("old-task", activated.remoteTaskId());
        assertTrue(task.getTagList().contains("new-subscription"));
    }

    @Test
    void thirdPartySameHashTaskIsNeverTaggedOrActivated() {
        TorrentsInfo thirdParty = oldOwnedTask("old-task", "same-hash", "/downloads", "external", "old-subscription");
        thirdParty.setTagList(new ArrayList<>(List.of("old-subscription")));
        fakeClient.tasks.add(thirdParty);

        assertFalse(service.reattach(fakeClient.client(), replacement));
        assertTrue(fakeClient.addedTags.isEmpty());
        assertEquals(OwnershipState.PENDING, repository.find(replacement.ownershipId()).orElseThrow().state());
    }

    @Test
    void changedRemoteIdentityPathOrOldTagIsNeverTaggedOrActivated() {
        fakeClient.tasks.add(oldOwnedTask("other-task", "same-hash", "/downloads", "ani-rss", "old-subscription"));
        fakeClient.tasks.add(oldOwnedTask("old-task", "same-hash", "/different", "ani-rss", "old-subscription"));
        fakeClient.tasks.add(oldOwnedTask("old-task", "same-hash", "/downloads", "ani-rss", "other-subscription"));

        assertFalse(service.reattach(fakeClient.client(), replacement));
        assertTrue(fakeClient.addedTags.isEmpty());
        assertEquals(OwnershipState.PENDING, repository.find(replacement.ownershipId()).orElseThrow().state());
    }

    private DownloadOwnership reassignDeletedOwnership() {
        long now = System.currentTimeMillis();
        DownloadOwnership old = repository.createPending(new DownloadOwnership(
                "old-ownership", "qBittorrent", "old-task", "same-hash", "old-subscription",
                1, "1.0", "/downloads", OwnershipState.ACTIVE, now, now));
        repository.updateState(old.ownershipId(), OwnershipState.DELETED);
        return repository.createPending(new DownloadOwnership(
                "new-ownership", "qBittorrent", null, "same-hash", "new-subscription",
                1, "1.0", "/downloads", OwnershipState.PENDING, now + 1, now + 1));
    }

    private static TorrentsInfo oldOwnedTask(
            String id, String hash, String path, String category, String oldSubscriptionTag) {
        return new TorrentsInfo()
                .setId(id)
                .setHash(hash)
                .setSavePath(path)
                .setCategory(category)
                .setTagList(new ArrayList<>(List.of("ani-rss", oldSubscriptionTag)))
                .setState(TorrentsStateEnum.downloading)
                .setFilesSupplier(List::of);
    }

    private static final class FakeClient {
        private final List<TorrentsInfo> tasks = new ArrayList<>();
        private final List<String> addedTags = new ArrayList<>();

        private DownloaderClient client() {
            Config config = new Config().setDownloadToolType("qBittorrent")
                    .setDownloadToolHost("http://not-used")
                    .setDownloadToolUsername("not-used")
                    .setDownloadToolPassword("not-used");
            return new DownloaderClient(new qBittorrent(new DownloadService(), config) {
                @Override
                public List<TorrentsInfo> getTorrentsInfos() {
                    return tasks;
                }

                @Override
                public Boolean addTags(TorrentsInfo task, String tag) {
                    addedTags.add(tag);
                    task.getTagList().add(tag);
                    return true;
                }
            }, config);
        }
    }
}
