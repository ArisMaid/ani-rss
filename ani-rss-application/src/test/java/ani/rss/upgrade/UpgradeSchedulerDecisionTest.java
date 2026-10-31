package ani.rss.upgrade;

import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradeSchedulerDecisionTest {
    @Test void gardenFailureStillEvaluatesPreviouslyKnownResourcesAndRssRunsFirst() throws Exception {
        target.setLibraryId("UNASSIGNED"); target.setPolicyOverride("p");
        var resolver=mock(SubjectEpisodeResolver.class); when(resolver.enrichStep(target)).thenReturn(true);
        var provider=mock(ReleaseProvider.class); when(provider.scanStep(target)).thenThrow(new UpgradeHttp.SourceFailure("UPSTREAM_HTTP_503",true));
        var local=new UpgradeScheduler(repository,new UpgradeCampaignService(repository,new QualityPolicyEngine()),null,mock(EmbyLibraryIndexService.class),provider,mock(AiAnalysisClient.class),new QualityPolicyEngine(),downloads,commits,space,null,resolver,null,mock(UpgradeNotificationService.class));
        Job scan=new Job(); scan.setId("scan"); scan.setKind("TARGET"); scan.setTargetId("t");
        repository.save("upgrade_targets","t","UNASSIGNED","100:UNASSIGNED","MONITORING",1,target);
        assertThrows(UpgradeHttp.SourceFailure.class,()->local.process(scan));
        var order=inOrder(provider); order.verify(provider).rss(any(Target.class)); order.verify(provider).scanStep(any(Target.class)); order.verify(provider).refreshStoredEvidence(any(Target.class));
        assertEquals("UPGRADE",repository.get("upgrade_releases","a",Release.class).orElseThrow().getState());
        assertTrue(repository.all("upgrade_jobs","t",null,Job.class).isEmpty());
    }
    @Test void cancelledTitleDoesNotRunQueuedDiscoveryOrSubmitDownloads() throws Exception {
        Campaign campaign=repository.get("upgrade_campaigns","c",Campaign.class).orElseThrow(); campaign.setExcludedMikanSubjects(Map.of("1","100"));
        repository.save("upgrade_campaigns","c",null,null,"ENABLED",2,campaign);
        Job scan=new Job(); scan.setId("cancelled-scan"); scan.setKind("TARGET"); scan.setTargetId("t");
        scheduler.process(scan);
        assertEquals(JobState.COMPLETED,scan.getState()); verifyNoInteractions(downloads);
        scheduler.evaluateTarget(target);
        assertTrue(repository.all("upgrade_jobs","t",null,Job.class).stream().noneMatch(j->"UPGRADE".equals(j.getKind())));
    }
    @Test void monitorOnlyTargetCollectsDecisionsWithoutCreatingTransferJobs() {
        target.setLibraryId("UNASSIGNED");
        var campaign=repository.get("upgrade_campaigns","c",Campaign.class).orElseThrow(); campaign.setLibraryId("UNASSIGNED");
        repository.save("upgrade_campaigns","c",null,null,"ENABLED",1,campaign);
        scheduler.evaluateTarget(target);
        assertTrue(repository.all("upgrade_jobs","t",null,Job.class).isEmpty());
        assertEquals("MONITORING",target.getState()); verifyNoInteractions(downloads);
    }
    @TempDir Path directory;
    private String prior;
    private Config original;
    private UpgradeRepository repository;
    private UpgradeCommitService commits;
    private UpgradeScheduler scheduler;
    private UpgradeDownloadService downloads;
    private SpaceReservationService space;
    private QualityPolicyEngine.Policy policy;
    private Target target;
    private Release preferred,alternative;
    @BeforeEach void before() throws Exception {
        prior=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        var settings=new UpgradeSettings(); settings.setLibraryIds(List.of("lib")); settings.setReserveBytes(0); settings.setTvRoot(Files.createDirectory(directory.resolve("library")).toString());
        ConfigUtil.CONFIG.setUpgrade(settings).setUpgradeMode(UpgradeMode.AUTOMATIC).setUpgradeModeRevision(1L);
        repository=new UpgradeRepository(); space=spy(new SpaceReservationService()); commits=new UpgradeCommitService(repository,new LibraryPathMappingService(),space);
        policy=new QualityPolicyEngine.Policy(); policy.setId("p"); policy.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION)); savePolicy();
        Campaign campaign=new Campaign(); campaign.setId("c"); campaign.setLibraryId("lib"); campaign.setPolicyId("p"); campaign.setStartDate("2024-01-01"); campaign.setEndDate("2024-12-31");
        repository.save("upgrade_campaigns","c",null,null,"ENABLED",1,campaign);
        target=new Target(); target.setId("t"); target.setSubjectId("100"); target.setLibraryId("lib"); target.setPremiereDate("2024-04-01"); target.setSeason(1); target.setOutputRoot(settings.getTvRoot());
        repository.save("upgrade_targets","t","lib","100:lib","MONITORING",1,target);
        preferred=release("a",2160); alternative=release("b",1080);
        downloads=mock(UpgradeDownloadService.class); var emby=mock(EmbyLibraryIndexService.class); when(emby.fresh("lib")).thenReturn(true);
        scheduler=new UpgradeScheduler(repository,new UpgradeCampaignService(repository,new QualityPolicyEngine()),null,emby,mock(ReleaseProvider.class),mock(AiAnalysisClient.class),new QualityPolicyEngine(),downloads,commits,space,null,null,null,mock(UpgradeNotificationService.class));
    }
    @AfterEach void after() { DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }
    private void savePolicy() { repository.save("upgrade_policies","p",null,null,"ACTIVE",policy.getRevision(),policy); }
    private Release release(String id,int resolution) {
        Release release=new ReleaseAnalysisService().parse("[A] Example - 01 ["+resolution+"p][CHS][MKV]","100","FIXTURE");
        release.setId(id); release.setTargetId("t"); release.setSeason(1); release.setInfoHash(id.repeat(40)); saveRelease(release); return release;
    }
    private void saveRelease(Release release) { repository.save("upgrade_releases",release.getId(),"t","t:"+release.getInfoHash(),release.getState(),release.getRevision(),release); }
    private Job negative() {
        Job job=new Job(); job.setId("negative"); job.setTargetId("t"); job.setReleaseId("a"); job.setRemoteTaskId("existing-transfer");
        job.setIdentityKey("download:t:"+preferred.getInfoHash()+":"+target.getOutputRoot()); job.setState(JobState.NOT_BETTER);
        job.setDecisionFingerprint(commits.comparisonFingerprint(target,policy,preferred)); commits.saveJob(job); return job;
    }
    @Test void negativeVerificationDoesNotStarveTheNextAvailableCandidateOrCreateRepeatedJobs() {
        negative(); scheduler.evaluateTarget(target); scheduler.evaluateTarget(target);
        List<Job> jobs=repository.all("upgrade_jobs","t",null,Job.class); assertEquals(2,jobs.size());
        assertEquals(JobState.NOT_BETTER,repository.get("upgrade_jobs","negative",Job.class).orElseThrow().getState());
        assertTrue(jobs.stream().anyMatch(j->"b".equals(j.getReleaseId())&&j.getState()==JobState.QUEUED)); verifyNoInteractions(downloads);
    }
    @Test void ruleOrParsedFieldChangesReuseTheExistingTransferForVerification() {
        negative(); policy.setRevision(2); savePolicy(); scheduler.evaluateTarget(target);
        assertEquals(JobState.VERIFYING,repository.get("upgrade_jobs","negative",Job.class).orElseThrow().getState());
        negative(); preferred.getLanguages().add("JPN"); saveRelease(preferred); scheduler.evaluateTarget(target);
        Job job=repository.get("upgrade_jobs","negative",Job.class).orElseThrow(); assertEquals(JobState.VERIFYING,job.getState()); assertEquals("existing-transfer",job.getRemoteTaskId()); verifyNoInteractions(downloads);
    }
    @Test void changedBaselineOrProtectionInvalidatesTheNegativeDecision() {
        negative(); Version version=new Version(); version.setId("v"); version.setTargetId("t"); version.setAttributes(new ReleaseAnalysisService().parse("[A] Example - 01 [720p][CHS][MKV]","100","FIXTURE")); version.getAttributes().setSeason(1);
        repository.save("upgrade_versions","v","t",null,"ACTIVE",1,version); scheduler.evaluateTarget(target);
        assertEquals(JobState.VERIFYING,repository.get("upgrade_jobs","negative",Job.class).orElseThrow().getState());
        negative(); version.setProtectedVersion(true); repository.save("upgrade_versions","v","t",null,"ACTIVE",1,version); scheduler.evaluateTarget(target);
        assertEquals(JobState.VERIFYING,repository.get("upgrade_jobs","negative",Job.class).orElseThrow().getState());
    }
    @Test void completedTransferReleasesItsReservationEvenWhenActualMediaIsNotEligible() throws Exception {
        Job job=negative(); job.setState(JobState.DOWNLOADING); job.setReservedBytes(100); commits.saveJob(job);
        var task=new TorrentsInfo().setId("existing-transfer").setState(TorrentsStateEnum.uploading).setProgress(100D);
        when(downloads.task(eq(job),any(Release.class))).thenReturn(Optional.of(task));
        JobFile file=new JobFile(); file.setAttributes(new ReleaseAnalysisService().parse("[A] Example - 01 [480p][CHS][MKV]","100","ACTUAL")); file.getAttributes().setSeason(1);
        when(downloads.verify(eq(job),any(Target.class),any(Release.class),eq(task))).thenReturn(List.of(file)); scheduler.process(job);
        assertEquals(JobState.NOT_BETTER,job.getState()); assertEquals(0,job.getReservedBytes()); assertTrue(job.isRetryable()); assertEquals("NOT_BETTER_AFTER_VERIFICATION",job.getReasonCode());
        verify(space).release("download:negative"); verify(downloads,never()).submit(any(),any());
    }
    @Test void aProtectedOldVersionCanBeRetainedWhileABetterNewVersionIsInstalled() throws Exception {
        Path oldFile=Files.writeString(Path.of(target.getOutputRoot()).resolve("protected.mkv"),"protected fixture");
        Version old=new Version(); old.setId("protected"); old.setTargetId("t"); old.setPath(oldFile.toString()); old.setProtectedVersion(true);
        old.setAttributes(alternative); repository.save("upgrade_versions",old.getId(),"t",null,"ACTIVE",1,old);
        Job job=negative(); job.setState(JobState.DOWNLOADING); commits.saveJob(job);
        var task=new TorrentsInfo().setId("existing-transfer").setState(TorrentsStateEnum.uploading).setProgress(100D); when(downloads.task(eq(job),any(Release.class))).thenReturn(Optional.of(task));
        Path source=Files.writeString(directory.resolve("verified.mkv"),"verified new fixture");
        JobFile file=new JobFile(); file.setAttributes(preferred); file.setSource(source.toString()); file.setDestination(Path.of(target.getOutputRoot()).resolve("new.mkv").toString()); file.setSize(Files.size(source)); file.setSha256(MediaVerificationService.sha256(source));
        when(downloads.verify(eq(job),any(Target.class),any(Release.class),eq(task))).thenReturn(List.of(file)); scheduler.process(job);
        assertEquals(JobState.COMPLETED,job.getState()); assertEquals("protected fixture",Files.readString(oldFile)); assertTrue(Files.exists(Path.of(file.getDestination())));
        assertEquals(2,repository.all("upgrade_versions","t","ACTIVE",Version.class).size());
        assertTrue(repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().noneMatch(f->"DELETE_OLD".equals(f.getAction())));
    }
}
