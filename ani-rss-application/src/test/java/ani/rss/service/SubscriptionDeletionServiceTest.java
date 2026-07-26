package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnedFile;
import ani.rss.ownership.OwnershipRepository;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.persistence.DatabaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionDeletionServiceTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private FakeSubscriptionStore store;
    private FakeRemoteTasks remoteTasks;
    private SubscriptionDeletionService service;
    private Path ownedFile;

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        repository = new OwnershipRepository();
        OwnershipService ownershipService = new OwnershipService(repository);
        store = new FakeSubscriptionStore(List.of(subscription("subscription", "Example")));
        remoteTasks = new FakeRemoteTasks();
        service = new SubscriptionDeletionService(
                ownershipService, store, remoteTasks);

        Path root = Files.createDirectories(tempDir.resolve("downloads").resolve("Example"));
        ownedFile = root.resolve("season-1").resolve("episode.mkv");
        Files.createDirectories(ownedFile.getParent());
        Files.writeString(ownedFile, "episode");
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                "ownership", "qBittorrent", "remote-task", "info-hash", "subscription",
                1, "1.0", root.toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles("ownership", List.of(
                new OwnedFile("ownership", "season-1/episode.mkv", "FILE", 7L)));
        remoteTasks.tasks.add(task("remote-task", "info-hash"));
        remoteTasks.tasks.add(task("same-hash-unowned", "info-hash"));
        remoteTasks.tasks.add(task("unowned-task", "other-hash"));
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void deletesExactFilesAndOnlyOwnedRemoteTasksImmediately() throws Exception {
        Path unrelated = ownedFile.getParent().resolve("unrelated.mkv");
        Files.writeString(unrelated, "unrelated");

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertFalse(Files.exists(ownedFile));
        assertTrue(Files.exists(unrelated));
        assertTrue(store.snapshot().isEmpty());
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(1, result.deletedSubscriptions());
        assertEquals(1, result.deletedRemoteTasks());
        assertEquals(1, result.deletedFiles());
        assertEquals(OwnershipState.DELETED,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void deletesEmptySubscriptionDirectoryButNotItsDownloadBase() {
        Path subscriptionRoot = ownedFile.getParent().getParent();
        Path downloadBase = subscriptionRoot.getParent();

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertFalse(Files.exists(ownedFile));
        assertFalse(Files.exists(ownedFile.getParent()));
        assertFalse(Files.exists(subscriptionRoot));
        assertTrue(Files.isDirectory(downloadBase));
        assertEquals(1, result.deletedFiles());
    }

    @Test
    void retainsAnEmptySharedSaveRootForAnotherLiveOwnership() {
        long now = System.currentTimeMillis();
        Path sharedRoot = ownedFile.getParent().getParent();
        repository.createPending(new DownloadOwnership(
                "other-pending", "qBittorrent", null, "other-hash", "other-subscription",
                1, "1.0", sharedRoot.toString(), OwnershipState.PENDING, now, now));

        service.delete(List.of("subscription"), true);

        assertTrue(Files.isDirectory(sharedRoot));
        assertFalse(Files.exists(ownedFile));
    }

    @Test
    void deletionReleasesPendingAndFailedOwnershipsForFutureSubscriptions() {
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                "pending-ownership", "qBittorrent", null, "pending-hash", "subscription",
                1, "2.0", ownedFile.getParent().toString(), OwnershipState.PENDING, now, now));
        repository.createPending(new DownloadOwnership(
                "failed-ownership", "qBittorrent", null, "failed-hash", "subscription",
                1, "3.0", ownedFile.getParent().toString(), OwnershipState.FAILED, now, now));

        service.delete(List.of("subscription"), false);

        assertEquals(OwnershipState.DELETED,
                repository.find("ownership").orElseThrow().state());
        assertEquals(OwnershipState.DELETED,
                repository.find("pending-ownership").orElseThrow().state());
        assertEquals(OwnershipState.DELETED,
                repository.find("failed-ownership").orElseThrow().state());
    }

    @Test
    void remoteFailureLeavesFilesAndSubscriptionUntouched() {
        remoteTasks.failDelete = true;

        assertThrows(IllegalStateException.class, () -> service.delete(List.of("subscription"), true));

        assertTrue(Files.exists(ownedFile));
        assertEquals("episode", read(ownedFile));
        assertEquals(1, store.snapshot().size());
        assertEquals(OwnershipState.ACTIVE,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void persistenceFailureLeavesSubscriptionVisibleAfterDirectDeleteFailure() {
        store.failCommit = true;

        assertThrows(IllegalStateException.class, () -> service.delete(List.of("subscription"), true));

        assertEquals(1, store.snapshot().size());
        assertFalse(Files.exists(ownedFile));
        assertEquals(OwnershipState.DELETED,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void deletingSubscriptionWithoutFilesKeepsMediaButRemovesOwnedRemoteTasks() {
        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), false);

        assertTrue(Files.exists(ownedFile));
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertTrue(store.snapshot().isEmpty());
        assertEquals(1, result.deletedRemoteTasks());
        assertEquals(0, result.deletedFiles());
    }

    @Test
    void completionStyleDeletionKeepsMediaAndRemoteTasks() {
        SubscriptionDeletionService.DeletionResult result = service.deleteWithoutFiles(List.of("subscription"));

        assertTrue(Files.exists(ownedFile));
        assertTrue(remoteTasks.deletedIds.isEmpty());
        assertTrue(store.snapshot().isEmpty());
        assertEquals(0, result.deletedRemoteTasks());
        assertEquals(0, result.deletedFiles());
        assertEquals(OwnershipState.ACTIVE,
                repository.find("ownership").orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> repository.createPending(new DownloadOwnership(
                "replacement", "qBittorrent", null, "info-hash", "replacement-subscription",
                1, "1.0", ownedFile.getParent().toString(), OwnershipState.PENDING,
                System.currentTimeMillis(), System.currentTimeMillis())));
    }

    @Test
    void staleOwnedFileDoesNotBlockSubscriptionDeletion() throws Exception {
        Files.delete(ownedFile);

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertTrue(store.snapshot().isEmpty());
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.skippedFiles());
        assertEquals(OwnershipState.DELETED,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void sharedOwnedFileIsRetainedWhenAnotherSubscriptionStillOwnsIt() {
        store.add(subscription("other-subscription", "Other"));
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                "other-ownership", "qBittorrent", "other-task", "other-hash", "other-subscription",
                1, "1.0", ownedFile.getParent().getParent().toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles("other-ownership", List.of(
                new OwnedFile("other-ownership", "season-1/episode.mkv", "FILE", 7L)));
        remoteTasks.tasks.add(task("other-task", "other-hash"));

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertTrue(Files.exists(ownedFile));
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(List.of("other-subscription"), store.snapshot().stream().map(Ani::getId).toList());
        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.skippedFiles());
        assertEquals(OwnershipState.DELETED,
                repository.find("ownership").orElseThrow().state());
        assertEquals(OwnershipState.ACTIVE,
                repository.find("other-ownership").orElseThrow().state());
    }

    @Test
    void pathTraversalInManifestDoesNotEscapeTheOwnedRoot() throws Exception {
        Path outside = ownedFile.getParent().getParent().getParent().resolve("outside.mkv");
        Files.writeString(outside, "outside");
        repository.replaceFiles("ownership", List.of(
                new OwnedFile("ownership", "../outside.mkv", "FILE", 7L)));

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertTrue(Files.exists(outside));
        assertTrue(Files.exists(ownedFile));
        assertTrue(store.snapshot().isEmpty());
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.skippedFiles());
    }

    @Test
    void changedOwnedFileSizeIsRetainedInsteadOfDeleted() throws Exception {
        Files.writeString(ownedFile, "changed-content");

        SubscriptionDeletionService.DeletionResult result = service.delete(List.of("subscription"), true);

        assertTrue(Files.exists(ownedFile));
        assertEquals("changed-content", read(ownedFile));
        assertTrue(store.snapshot().isEmpty());
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(0, result.deletedFiles());
        assertEquals(1, result.skippedFiles());
    }

    private static Ani subscription(String id, String title) {
        return new Ani().setId(id).setTitle(title).setSeason(1);
    }

    private static TorrentsInfo task(String id, String hash) {
        return new TorrentsInfo().setId(id).setHash(hash).setName(id);
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Ani copy(Ani ani) {
        return GsonStatic.GSON.fromJson(GsonStatic.toJson(ani), Ani.class);
    }

    private static final class FakeSubscriptionStore
            implements SubscriptionDeletionService.SubscriptionStore {
        private final List<Ani> values = new ArrayList<>();
        private boolean failCommit;

        private FakeSubscriptionStore(List<Ani> initial) {
            initial.forEach(ani -> values.add(copy(ani)));
        }

        private void add(Ani ani) {
            values.add(copy(ani));
        }

        @Override
        public List<Ani> snapshot() {
            return values.stream().map(SubscriptionDeletionServiceTest::copy).toList();
        }

        @Override
        public void commit(List<Ani> candidate) {
            if (failCommit) {
                throw new IllegalStateException("injected persistence failure");
            }
            values.clear();
            candidate.forEach(ani -> values.add(copy(ani)));
        }
    }

    private static final class FakeRemoteTasks
            implements SubscriptionDeletionService.RemoteTaskGateway {
        private final List<TorrentsInfo> tasks = new ArrayList<>();
        private final List<String> deletedIds = new ArrayList<>();
        private boolean failDelete;

        @Override
        public boolean supports(String downloaderType) {
            return "qBittorrent".equals(downloaderType);
        }

        @Override
        public List<TorrentsInfo> list() {
            return List.copyOf(tasks);
        }

        @Override
        public void deleteTaskOnly(TorrentsInfo task) {
            if (failDelete) {
                throw new IllegalStateException("injected downloader failure");
            }
            deletedIds.add(task.getId());
        }
    }
}
