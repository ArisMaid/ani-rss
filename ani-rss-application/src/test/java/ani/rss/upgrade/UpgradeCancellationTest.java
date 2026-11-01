package ani.rss.upgrade;

import ani.rss.download.*;
import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradeCancellationTest {
    @TempDir Path temporary;
    private String oldConfig;
    private Config original;
    private UpgradeRepository repository;
    private UpgradeDownloadService downloads;
    private SpaceReservationService space;
    private DownloaderClient client;
    private MockedStatic<TorrentUtil> torrents;
    private Job job;
    private TorrentsInfo task;
    private Release release;
    @BeforeEach void setup() {
        oldConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",temporary.resolve("config").toString()); DatabaseManager.close();
        original=ConfigUtil.snapshot(); repository=new UpgradeRepository(); space=new SpaceReservationService();
        downloads=new UpgradeDownloadService(repository,new LibraryPathMappingService(),new MediaVerificationService(),new ReleaseAnalysisService(),space);
        job=new Job(); job.setId("cancel"); job.setTargetId("target"); job.setIdentityKey("cancel"); job.setState(JobState.DOWNLOADING);
        job.setDownloaderType(ConfigUtil.snapshot().getDownloadToolType()); job.setDownloaderIdentity(UpgradeDownloadService.downloaderIdentity(ConfigUtil.snapshot()));
        job.setRemoteTaskId("remote"); job.setReservedBytes(100); job.setStagingPath(temporary.toString()); save(job);
        release=new Release(); release.setInfoHash("a".repeat(40));
        task=new TorrentsInfo().setId("remote").setHash(release.getInfoHash()).setSavePath(temporary.toString());
        client=mock(DownloaderClient.class); when(client.inventory()).thenReturn(DownloaderResult.success(List.of(task))); when(client.delete(task,false)).thenReturn(DownloaderResult.success(null));
        torrents=mockStatic(TorrentUtil.class); torrents.when(TorrentUtil::client).thenReturn(client);
    }
    @AfterEach void close() {
        torrents.close(); ConfigUtil.sync(original); DatabaseManager.close();
        if(oldConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",oldConfig);
        ani.rss.util.basic.LogUtil.loadLogback();
    }
    private void save(Job value) { repository.save("upgrade_jobs",value.getId(),value.getTargetId(),value.getIdentityKey(),value.getState().name(),1,value); }
    private void own() { new UpgradeOwnershipService().pending(job,release,temporary.toString()); new UpgradeOwnershipService().activate(job); }
    private UpgradeScheduler scheduler() {
        return new UpgradeScheduler(repository,null,null,null,null,null,null,downloads,
                new UpgradeCommitService(repository,new LibraryPathMappingService(),space),space,null,null,null,null);
    }
    @Test void cancelledOwnedTransferStopsWithoutDeletingFilesAndCanBeSubmittedAgain() {
        own(); repository.requestCancellation(job.getId()); scheduler().cancelTransfer(job);
        assertEquals(JobState.CANCELLED,job.getState()); assertEquals("CANCELLED_TRANSFER_STOPPED",job.getReasonCode());
        assertEquals(0,job.getReservedBytes()); assertNull(job.getRemoteTaskId()); verify(client).delete(task,false);
        Job next=new Job(); next.setId("next"); next.setTargetId("target"); next.setDownloaderType(job.getDownloaderType()); next.setRemoteTaskId("replacement");
        new UpgradeOwnershipService().pending(next,release,temporary.toString()); assertTrue(new UpgradeOwnershipService().owns(next));
    }
    @Test void ordinaryReusedTransferIsNeverStopped() throws Exception {
        downloads.stopOwned(job); assertNull(job.getRemoteTaskId()); verifyNoInteractions(client);
    }
    @Test void sharedTransferMovesOwnershipAndReservationToTheSurvivingTarget() throws Exception {
        own(); Job survivor=new Job(); survivor.setId("survivor"); survivor.setTargetId("other-library-target"); survivor.setIdentityKey("survivor"); survivor.setState(JobState.DOWNLOADING);
        survivor.setDownloaderType(job.getDownloaderType()); survivor.setDownloaderIdentity(job.getDownloaderIdentity()); survivor.setRemoteTaskId(job.getRemoteTaskId()); save(survivor);
        space.reserve("download:"+job.getId(),temporary,100,0); repository.requestCancellation(job.getId()); scheduler().cancelTransfer(job);
        survivor=repository.get("upgrade_jobs","survivor",Job.class).orElseThrow();
        assertTrue(new UpgradeOwnershipService().owns(survivor)); assertFalse(new UpgradeOwnershipService().owns(job)); assertEquals(100,survivor.getReservedBytes());
        verifyNoInteractions(client); assertNull(job.getRemoteTaskId()); assertEquals(0,job.getReservedBytes());
    }
    @Test void stopFailureRetainsReservationAndIsRetriedEvenThoughCancellationIsTerminal() {
        own(); when(client.delete(task,false)).thenReturn(DownloaderResult.failed("OFFLINE",true)); repository.requestCancellation(job.getId());
        UpgradeScheduler scheduler=scheduler(); scheduler.cancelTransfer(job);
        assertEquals(JobState.CANCELLED,job.getState()); assertEquals("CANCEL_STOP_PENDING",job.getReasonCode()); assertEquals(100,job.getReservedBytes()); assertEquals("remote",job.getRemoteTaskId()); assertTrue(job.getRetryAt()>System.currentTimeMillis());
        when(client.delete(task,false)).thenReturn(DownloaderResult.success(null)); scheduler.cancelTransfer(job);
        assertEquals("CANCELLED_TRANSFER_STOPPED",job.getReasonCode()); assertEquals(0,job.getReservedBytes());
    }
    @Test void metadataOnlyMagnetCannotStartOrRecoverBeforeItsFilesMatchThePolicy() {
        var policy=new QualityPolicyEngine.Policy(); policy.setContainers(Set.of("mkv")); policy.setId("mkv-only");
        repository.save("upgrade_policies",policy.getId(),null,null,"ACTIVE",1,policy);
        var ani=new ani.rss.entity.Ani().setQualityPolicyId(policy.getId());
        var service=mock(ani.rss.service.DownloadService.class); when(service.findAniByDownloadPath(task)).thenReturn(Optional.of(ani));
        var adapter=spy(new ani.rss.download.qBittorrent(service,ConfigUtil.snapshot().setRename(false)));
        task.setTagList(List.of(SubscriptionQualitySelector.CONTAINER_CHECK_TAG));
        var file=new ani.rss.entity.torrent.qBittorrentTorrentsInfo.FileEntity().setName("episode.mp4");
        doReturn(List.of(file)).when(adapter).files(task,false);
        assertFalse(adapter.rename(task)); assertFalse(adapter.recoverResult(task).isSuccess());
        verify(adapter,never()).start(any());
        file.setName("episode.mkv"); doReturn(true).when(adapter).start(task); assertTrue(adapter.rename(task));
    }
    @Test void disablingRenameStillSelectsTheRequestedChaptersBeforeStartingTheBatch() {
        var subscription = new ani.rss.entity.Ani().setOffset(0).setOva(false).setCustomPriorityKeywordsEnable(false);
        var service = mock(ani.rss.service.DownloadService.class);
        when(service.findAniByDownloadPath(task)).thenReturn(Optional.of(subscription));
        var adapter = spy(new qBittorrent(service, ConfigUtil.snapshot().setRename(false)));
        task.setName("Example").setTagList(List.of(SubscriptionQualitySelector.COLLECTION_TAG,
                "ani-rss-offset=-12", "ani-rss-chapters=2"));
        var first = new ani.rss.entity.torrent.qBittorrentTorrentsInfo.FileEntity().setIndex(0).setName("Example S01E13.mkv");
        var second = new ani.rss.entity.torrent.qBittorrentTorrentsInfo.FileEntity().setIndex(1).setName("Example S01E14.mkv");
        doReturn(List.of(first, second)).when(adapter).files(task, true);
        doReturn(true).when(adapter).setFilePriority(task.getHash(), 0, 0);
        doReturn(true).when(adapter).start(task);
        assertTrue(adapter.rename(task));
        var order = inOrder(adapter);
        order.verify(adapter).setFilePriority(task.getHash(), 0, 0);
        order.verify(adapter).start(task);
        verify(adapter, never()).renameFile(anyString(), anyString(), anyString());
    }
    @Test void correctionDtoRejectsIdentityAndEnumsButAllowsAnExplicitContentEdition() {
        var scheduler=mock(UpgradeScheduler.class);
        var controller=new ani.rss.controller.v2.UpgradeController(repository,null,null,null,scheduler,null,null,null,null);
        release.setId("candidate"); release.setTargetId("target"); release.setSubjectId("100"); release.setSource("fixture"); release.setSourceId("candidate");
        repository.save("upgrade_releases",release.getId(),release.getTargetId(),"target:"+release.getInfoHash(),"WAITING_EVIDENCE",1,release);
        var correction=new ani.rss.controller.v2.UpgradeController.CandidateCorrection("100",Set.of("01.0"),1,Set.of("A"),Set.of("CHS"),null,"HARD",1080,"NORMAL","DIRECTORS_CUT");
        Release resolved=controller.resolve("candidate",correction); assertEquals("DIRECTORS_CUT",resolved.getContentEdition()); assertEquals(Set.of("1"),resolved.getEpisodes()); verify(scheduler).enqueue("TARGET","target");
        var conflict=assertThrows(ani.rss.exception.ApiProblemException.class,()->controller.resolve("candidate",new ani.rss.controller.v2.UpgradeController.CandidateCorrection("999",Set.of("1"),1,null,null,null,null,null,null,null)));
        assertEquals(409,conflict.status().value()); assertEquals("UPGRADE_RESOURCE_IDENTITY_CONFLICT",conflict.code());
        assertThrows(IllegalArgumentException.class,()->controller.resolve("candidate",new ani.rss.controller.v2.UpgradeController.CandidateCorrection("100",Set.of("1"),1,null,Set.of("FAKE"),null,null,null,null,null)));
    }
}
