package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnedFile;
import ani.rss.ownership.OwnershipRepository;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.ownership.QuarantineService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscriptionDeletionServiceTest {
    @TempDir
    Path tempDir;

    private OwnershipRepository repository;
    private QuarantineService quarantineService;
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
        quarantineService = new QuarantineService(ownershipService, repository);
        store = new FakeSubscriptionStore(List.of(subscription("subscription", "Example")));
        remoteTasks = new FakeRemoteTasks();
        service = new SubscriptionDeletionService(
                ownershipService, quarantineService, store, remoteTasks);

        Path root = Files.createDirectories(tempDir.resolve("downloads"));
        ownedFile = root.resolve("episode.mkv");
        Files.writeString(ownedFile, "episode");
        long now = System.currentTimeMillis();
        repository.createPending(new DownloadOwnership(
                "ownership", "qBittorrent", "remote-task", "info-hash", "subscription",
                1, "1.0", root.toString(), OwnershipState.ACTIVE, now, now));
        repository.replaceFiles("ownership", List.of(
                new OwnedFile("ownership", "episode.mkv", "FILE", 7L)));
        remoteTasks.tasks.add(task("remote-task", "info-hash"));
        remoteTasks.tasks.add(task("unowned-task", "other-hash"));
    }

    @AfterEach
    void tearDown() {
        DatabaseManager.close();
        System.clearProperty("CONFIG");
    }

    @Test
    void previewsExactFilesThenDeletesOnlyOwnedRemoteTask() {
        SubscriptionDeletionService.DeletionPlan plan =
                service.plan(List.of("subscription"), true);

        assertTrue(Files.exists(ownedFile));
        assertEquals(1, plan.files().size());
        assertEquals(ownedFile.toString(), plan.files().get(0).path());

        SubscriptionDeletionService.DeletionResult result = service.execute(plan.operationId());

        assertFalse(Files.exists(ownedFile));
        assertTrue(store.snapshot().isEmpty());
        assertEquals(List.of("remote-task"), remoteTasks.deletedIds);
        assertEquals(1, result.deletedSubscriptions());
        assertEquals(1, result.deletedRemoteTasks());
        assertNotNull(result.quarantineOperationId());
    }

    @Test
    void remoteFailureRestoresFilesAndKeepsSubscription() {
        SubscriptionDeletionService.DeletionPlan plan =
                service.plan(List.of("subscription"), true);
        remoteTasks.failDelete = true;

        assertThrows(IllegalStateException.class, () -> service.execute(plan.operationId()));

        assertTrue(Files.exists(ownedFile));
        assertEquals("episode", read(ownedFile));
        assertEquals(1, store.snapshot().size());
        assertEquals(OwnershipState.ACTIVE,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void persistenceFailureRestoresFilesAndKeepsRuntimeSnapshot() {
        SubscriptionDeletionService.DeletionPlan plan =
                service.plan(List.of("subscription"), true);
        store.failCommit = true;

        assertThrows(IllegalStateException.class, () -> service.execute(plan.operationId()));

        assertTrue(Files.exists(ownedFile));
        assertEquals(1, store.snapshot().size());
        assertEquals(OwnershipState.ACTIVE,
                repository.find("ownership").orElseThrow().state());
    }

    @Test
    void subscriptionChangeAfterPreviewLeavesDiskAndRemoteTasksUntouched() {
        SubscriptionDeletionService.DeletionPlan plan =
                service.plan(List.of("subscription"), true);
        store.values.get(0).setTitle("Changed");

        assertThrows(IllegalStateException.class, () -> service.execute(plan.operationId()));

        assertTrue(Files.exists(ownedFile));
        assertTrue(remoteTasks.deletedIds.isEmpty());
        assertEquals(1, store.snapshot().size());
    }

    @Test
    void deletingSubscriptionOnlyDoesNotTouchFilesOrDownloader() {
        SubscriptionDeletionService.DeletionPlan plan =
                service.plan(List.of("subscription"), false);

        SubscriptionDeletionService.DeletionResult result = service.execute(plan.operationId());

        assertTrue(Files.exists(ownedFile));
        assertTrue(remoteTasks.deletedIds.isEmpty());
        assertTrue(store.snapshot().isEmpty());
        assertEquals(0, result.deletedRemoteTasks());
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
