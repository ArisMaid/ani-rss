package ani.rss.recovery;

import ani.rss.download.DownloaderClient;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnedFile;
import ani.rss.ownership.OwnershipRepository;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.persistence.DatabaseManager;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MissingEpisodeRecoveryServiceTest {
    private static final String HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String HASH_C = "cccccccccccccccccccccccccccccccccccccccc";
    private static final String HASH_D = "dddddddddddddddddddddddddddddddddddddddd";

    @TempDir
    Path tempDir;

    private Config originalConfig;
    private DownloaderClient originalClient;
    private RecoveryRepository repository;
    private OwnershipRepository ownershipRepository;
    private OwnershipService ownershipService;
    private DownloadService downloadService;
    private MissingEpisodeRecoveryService service;
    private Path downloadRoot;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        originalConfig = ConfigUtil.snapshot();
        originalClient = TorrentUtil.client();
        System.setProperty("CONFIG", tempDir.resolve("config").toString());
        DatabaseManager.close();
        ConfigUtil.sync(ConfigUtil.copy(originalConfig)
                .setDownloadToolType("qBittorrent")
                .setMissingEpisodeRecoveryEnabled(true)
                .setStandbyRss(false)
                .setCoexist(false)
                .setRename(true)
                .setSkip5(false)
                .setFileExist(false)
                .setDownloadCount(0));

        repository = new RecoveryRepository();
        ownershipRepository = new OwnershipRepository();
        ownershipService = new OwnershipService(ownershipRepository);
        downloadRoot = Files.createDirectories(tempDir.resolve("downloads"));
        downloadService = mock(DownloadService.class);
        when(downloadService.getDownloadPath(any(Ani.class))).thenReturn(downloadRoot.toString());
        when(downloadService.itemDownloaded(any(Ani.class), any(Item.class), eq(false))).thenReturn(false);
        when(downloadService.recoverDownload(any(Ani.class), any(Item.class), anyString(), any(File.class)))
                .thenReturn(true);
        ObjectProvider<DownloadService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(downloadService);
        service = new MissingEpisodeRecoveryService(repository, ownershipService, provider);

        DownloaderClient client = mock(DownloaderClient.class);
        when(client.configurationSnapshot()).thenReturn(
                new Config().setDownloadToolType("qBittorrent"));
        setTorrentClient(client);
    }

    @AfterEach
    void tearDown() throws Exception {
        setTorrentClient(originalClient);
        DatabaseManager.close();
        ConfigUtil.sync(originalConfig);
        System.clearProperty("CONFIG");
    }

    @Test
    void sameNamedReleaseWithANewHashUsesHealthyOwnedMedia() throws Exception {
        Ani ani = ani();
        Item existing = item(HASH_A, 1.0,
                "[Group] Example - 01 [WEB-DL][1080P][A1B2C3D4][x265]", "old S01E01.mkv", true, "Group");
        Item replacement = item(HASH_B, 1.0,
                "[Group] Example - 01 [WEB-DL][1080P][FFFFFFFF][H.264]", "new S01E01.mkv", true, "Group");
        repository.observe(ani, existing);
        createHealthyOwnership(ani, existing, "existing.mkv");

        assertEquals(MissingEpisodeRecoveryService.SubmissionDisposition.TRACKED,
                service.prepareEligible(ani, replacement, false, downloadRoot.toString()));
        assertEquals(RecoveryState.SUPERSEDED,
                repository.find(ani.getId(), replacement.getInfoHash()).orElseThrow().state());

        service.coordinateCandidates(ani, List.of(replacement), "qBittorrent", true, downloadRoot.toString());

        assertEquals(RecoveryState.PENDING,
                repository.find(ani.getId(), existing.getInfoHash()).orElseThrow().state());
        assertEquals("RECOVERY_EQUIVALENT_PRESENT",
                repository.find(ani.getId(), replacement.getInfoHash()).orElseThrow().lastErrorCode());
    }

    @Test
    void duplicateCurrentCandidatesLeaveExactlyOneRecoverableKeeper() {
        Ani ani = ani();
        Item first = item(HASH_A, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "Example S01E01.mkv", true, "Group");
        Item second = item(HASH_B, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "Example S01E01.mp4", true, "Group");
        repository.observe(ani, first);
        repository.observe(ani, second);

        service.coordinateCandidates(ani, List.of(first, second), "qBittorrent", true, downloadRoot.toString());

        long recoverable = repository.listBySubscription(ani.getId()).stream()
                .filter(record -> record.state() != RecoveryState.SUPERSEDED)
                .filter(record -> record.state() != RecoveryState.CANCELLED)
                .count();
        assertEquals(1L, recoverable);
        assertEquals(RecoveryState.PENDING,
                repository.find(ani.getId(), first.getInfoHash()).orElseThrow().state());
        assertEquals(RecoveryState.SUPERSEDED,
                repository.find(ani.getId(), second.getInfoHash()).orElseThrow().state());
    }

    @Test
    void coexistKeepsVideoAndLiveVersionsAndDifferentSubgroups() {
        configure(config -> config.setCoexist(true).setStandbyRss(true));
        Ani ani = ani().setStandbyRssList(List.of(new StandbyRss().setLabel("Backup")));
        Item video = item(HASH_A, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "[Group] Example S01E01.mkv", true, "Group");
        Item live = item(HASH_B, 1.0, "[Group] Example - 01 [LiveVer][1080P]",
                "[Group] Example S01E01 Live.mkv", true, "Group");
        Item backup = item(HASH_C, 1.0, "[Backup] Example - 01 [VideoVer][1080P]",
                "[Backup] Example S01E01.mkv", false, "Backup");
        for (Item item : List.of(video, live, backup)) {
            repository.observe(ani, item);
        }

        service.coordinateCandidates(
                ani, List.of(video, live, backup), "qBittorrent", true, downloadRoot.toString());

        assertTrue(repository.listBySubscription(ani.getId()).stream()
                .allMatch(record -> record.state() == RecoveryState.PENDING));
    }

    @Test
    void coexistStopsAnOlderDistinctReleaseWhenFinalOutputCollides() {
        configure(config -> config.setCoexist(true).setRename(true));
        Ani ani = ani();
        Item old = item(HASH_A, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "Example S01E01.mkv", true, "Group");
        Item selected = item(HASH_B, 1.0, "[Group] Example - 01 [LiveVer][1080P]",
                "Example S01E01.mp4", true, "Group");
        repository.observe(ani, old);
        repository.observe(ani, selected);

        service.coordinateCandidates(ani, List.of(selected), "qBittorrent", true, downloadRoot.toString());

        RecoveryRecord oldRecord = repository.find(ani.getId(), old.getInfoHash()).orElseThrow();
        assertEquals(RecoveryState.SUPERSEDED, oldRecord.state());
        assertEquals("RECOVERY_OUTPUT_NAME_COLLISION", oldRecord.lastErrorCode());
        assertEquals(RecoveryState.PENDING,
                repository.find(ani.getId(), selected.getInfoHash()).orElseThrow().state());
    }

    @Test
    void nonCoexistFollowsTheCurrentMainRssSelection() {
        configure(config -> config.setStandbyRss(true).setCoexist(false));
        Ani ani = ani().setStandbyRssList(List.of(new StandbyRss().setLabel("Backup")));
        Item backup = item(HASH_A, 1.0, "[Backup] Example - 01 [VideoVer][1080P]",
                "[Backup] Example S01E01.mkv", false, "Backup");
        Item main = item(HASH_B, 1.0, "[Group] Example - 01 [LiveVer][1080P]",
                "[Group] Example S01E01.mkv", true, "Group");
        repository.observe(ani, backup);
        repository.observe(ani, main);

        service.coordinateCandidates(ani, List.of(main), "qBittorrent", true, downloadRoot.toString());

        assertEquals(RecoveryState.SUPERSEDED,
                repository.find(ani.getId(), backup.getInfoHash()).orElseThrow().state());
        assertEquals(RecoveryState.PENDING,
                repository.find(ani.getId(), main.getInfoHash()).orElseThrow().state());
    }

    @Test
    void sourceRemovalDownloadNewAndSkipHalfEpisodeStopHistoricalRecovery() {
        Ani ani = ani();
        Item disabledBackup = item(HASH_A, 1.0, "[Backup] Example - 01 [1080P]",
                "Backup S01E01", false, "Backup");
        repository.observe(ani, disabledBackup);
        service.coordinateCandidates(ani, List.of(), "qBittorrent", true, downloadRoot.toString());
        assertReason(ani, disabledBackup, "RECOVERY_SOURCE_DISABLED");

        Ani latestOnly = ani().setId("latest-only").setDownloadNew(true);
        Item historical = item(HASH_B, 1.0, "[Group] Example - 01 [1080P]",
                "Example S01E01", true, "Group");
        Item latest = item(HASH_C, 2.0, "[Group] Example - 02 [1080P]",
                "Example S01E02", true, "Group");
        repository.observe(latestOnly, historical);
        repository.observe(latestOnly, latest);
        service.coordinateCandidates(latestOnly, List.of(latest), "qBittorrent", true, downloadRoot.toString());
        assertReason(latestOnly, historical, "RECOVERY_DOWNLOAD_NEW_POLICY");

        configure(config -> config.setSkip5(true));
        Ani halfEpisodeAni = ani().setId("half-episode");
        Item half = item(HASH_D, 1.5, "[Group] Example - 01.5 [1080P]",
                "Example S01E01.5", true, "Group");
        repository.observe(halfEpisodeAni, half);
        service.coordinateCandidates(halfEpisodeAni, List.of(), "qBittorrent", true, downloadRoot.toString());
        assertReason(halfEpisodeAni, half, "RECOVERY_HALF_EPISODE_DISABLED");
    }

    @Test
    void subgroupScopedFiltersAreSharedWithTheNormalRssPath() {
        Ani ani = ani().setExclude(List.of("{{Backup}}:VideoVer"));
        Config config = ConfigUtil.snapshot();
        Item main = item(HASH_A, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "main", true, "Group");
        Item backup = item(HASH_B, 1.0, "[Backup] Example - 01 [VideoVer][1080P]",
                "backup", false, "Backup");

        assertTrue(ItemsUtil.isAllowedByDownloadRules(ani, main, config));
        assertFalse(ItemsUtil.isAllowedByDownloadRules(ani, backup, config));
    }

    @Test
    void disablingRecoveryPausesRetriesButStillSubmitsADeferredItemOnce() {
        configure(config -> config.setMissingEpisodeRecoveryEnabled(false));
        Ani ani = ani();
        Item retry = item(HASH_A, 1.0, "[Group] Example - 01 [1080P]",
                "Example S01E01", true, "Group");
        Item deferred = item(HASH_B, 2.0, "[Group] Example - 02 [1080P]",
                "Example S01E02", true, "Group");
        repository.observe(ani, retry);
        repository.scheduleRetry(ani.getId(), retry.getInfoHash(), "FAILED", 0L);
        repository.observe(ani, deferred);
        repository.defer(ani.getId(), deferred.getInfoHash(), 0L);
        TorrentUtil.saveTorrent(ani, retry);
        TorrentUtil.saveTorrent(ani, deferred);

        service.reconcile(ani, List.of(), List.of(), 0L);

        verify(downloadService, times(1)).recoverDownload(any(Ani.class),
                argThat(item -> HASH_B.equals(item.getInfoHash())), anyString(), any(File.class));
        assertEquals(RecoveryState.RETRY_WAIT,
                repository.find(ani.getId(), retry.getInfoHash()).orElseThrow().state());
        assertEquals(RecoveryState.SUBMITTED,
                repository.find(ani.getId(), deferred.getInfoHash()).orElseThrow().state());

        clearInvocations(downloadService);
        configure(config -> config.setMissingEpisodeRecoveryEnabled(true));
        service.reconcile(ani, List.of(), List.of(), 0L);

        verify(downloadService, times(1)).recoverDownload(any(Ani.class),
                argThat(item -> HASH_A.equals(item.getInfoHash())), anyString(), any(File.class));
        assertEquals(RecoveryState.SUBMITTED,
                repository.find(ani.getId(), retry.getInfoHash()).orElseThrow().state());
    }

    @Test
    void localFilePolicySatisfiesRecoveryBeforeAnySubmission() {
        configure(config -> config.setFileExist(true).setRename(true));
        Ani ani = ani();
        Item item = item(HASH_A, 1.0, "[Group] Example - 01 [VideoVer][1080P]",
                "Custom Name S01E01.mkv", true, "Group");
        repository.observe(ani, item);
        repository.scheduleRetry(ani.getId(), item.getInfoHash(), "FAILED", 0L);
        when(downloadService.itemDownloaded(any(Ani.class), any(Item.class), eq(false))).thenReturn(true);

        service.reconcile(ani, List.of(), List.of(), 0L);

        assertEquals(RecoveryState.SATISFIED,
                repository.find(ani.getId(), item.getInfoHash()).orElseThrow().state());
        verify(downloadService).itemDownloaded(any(Ani.class),
                argThat(candidate -> "Custom Name S01E01.mkv".equals(candidate.getReName())), eq(false));
        verify(downloadService, never()).recoverDownload(
                any(Ani.class), any(Item.class), anyString(), any(File.class));
    }

    @Test
    void recoverySubmissionsShareTheNormalDownloadLimit() {
        configure(config -> config.setDownloadCount(1));
        Ani ani = ani();
        Item first = item(HASH_A, 1.0, "[Group] Example - 01 [1080P]",
                "Example S01E01", true, "Group");
        Item second = item(HASH_B, 2.0, "[Group] Example - 02 [1080P]",
                "Example S01E02", true, "Group");
        for (Item item : List.of(first, second)) {
            repository.observe(ani, item);
            repository.scheduleRetry(ani.getId(), item.getInfoHash(), "FAILED", 0L);
            TorrentUtil.saveTorrent(ani, item);
        }

        service.reconcile(ani, List.of(), List.of(), 1L);
        verify(downloadService, never()).recoverDownload(
                any(Ani.class), any(Item.class), anyString(), any(File.class));

        service.reconcile(ani, List.of(), List.of(), 0L);
        verify(downloadService, times(1)).recoverDownload(
                any(Ani.class), any(Item.class), anyString(), any(File.class));
        long submitted = repository.listBySubscription(ani.getId()).stream()
                .filter(record -> record.state() == RecoveryState.SUBMITTED)
                .count();
        assertEquals(1L, submitted);
    }

    @Test
    void explicitNotDownloadAndCancellationCannotBeReactivated() {
        Ani ani = ani().setNotDownload(List.of(1.0));
        Item excluded = item(HASH_A, 1.0, "[Group] Example - 01 [1080P]",
                "Example S01E01", true, "Group");
        repository.observe(ani, excluded);
        repository.scheduleRetry(ani.getId(), excluded.getInfoHash(), "FAILED", 0L);

        service.reconcile(ani, List.of(), List.of(), 0L);
        assertEquals(RecoveryState.CANCELLED,
                repository.find(ani.getId(), excluded.getInfoHash()).orElseThrow().state());

        service.coordinateCandidates(ani, List.of(excluded), "qBittorrent", true, downloadRoot.toString());
        assertEquals(RecoveryState.CANCELLED,
                repository.find(ani.getId(), excluded.getInfoHash()).orElseThrow().state());
    }

    private Ani ani() {
        return new Ani()
                .setId("subscription")
                .setTitle("Example")
                .setSeason(1)
                .setEnable(true)
                .setOva(false)
                .setDownloadNew(false)
                .setNotDownload(new ArrayList<>())
                .setExclude(new ArrayList<>())
                .setMatch(new ArrayList<>())
                .setGlobalExclude(false)
                .setStandbyRssList(new ArrayList<>());
    }

    private static Item item(
            String hash,
            double episode,
            String title,
            String output,
            boolean master,
            String subgroup) {
        return new Item()
                .setInfoHash(hash)
                .setEpisode(episode)
                .setTitle(title)
                .setReName(output)
                .setMaster(master)
                .setSubgroup(subgroup)
                .setTorrent("magnet:?xt=urn:btih:" + hash);
    }

    private void configure(Consumer<Config> change) {
        Config config = ConfigUtil.snapshot();
        change.accept(config);
        ConfigUtil.sync(config);
    }

    private void createHealthyOwnership(Ani ani, Item item, String relativePath) throws Exception {
        Path media = downloadRoot.resolve(relativePath);
        Files.writeString(media, "media");
        long now = System.currentTimeMillis();
        String ownershipId = "ownership-" + item.getInfoHash();
        ownershipRepository.createPending(new DownloadOwnership(
                ownershipId,
                "qBittorrent",
                "remote-" + item.getInfoHash(),
                item.getInfoHash(),
                ani.getId(),
                ani.getSeason(),
                item.getEpisode().toString(),
                downloadRoot.toString(),
                OwnershipState.ACTIVE,
                now,
                now));
        ownershipRepository.replaceFiles(ownershipId,
                List.of(new OwnedFile(ownershipId, relativePath, "FILE", Files.size(media))));
    }

    private void assertReason(Ani ani, Item item, String reason) {
        RecoveryRecord record = repository.find(ani.getId(), item.getInfoHash()).orElseThrow();
        assertEquals(RecoveryState.SUPERSEDED, record.state());
        assertEquals(reason, record.lastErrorCode());
    }

    private static void setTorrentClient(DownloaderClient client) throws Exception {
        Field field = TorrentUtil.class.getDeclaredField("CLIENT");
        field.setAccessible(true);
        field.set(null, client);
    }
}
