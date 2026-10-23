package ani.rss.upgrade;

import ani.rss.download.*;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradeCommitServiceTest {
    @TempDir Path temporary;
    private Config original;
    private String oldConfig;
    private UpgradeRepository repository;
    private UpgradeCommitService service;
    private Target target;
    private Job job;
    private Version old;
    private Path oldFile,source,untouched;
    private List<JobFile> incoming;
    private MockedStatic<TorrentUtil> torrents;

    @BeforeEach void configure() throws Exception {
        oldConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",temporary.resolve("config").toString()); DatabaseManager.close();
        original=ConfigUtil.snapshot(); Config candidate=ConfigUtil.snapshot();
        Path library=Files.createDirectories(temporary.resolve("library"));
        var settings=new UpgradeSettings(); settings.setTvRoot(library.toString()); settings.setMovieRoot(library.toString()); settings.setReserveBytes(0);
        settings.setEmbyMappings(List.of(new UpgradeSettings.PathMapping("/media",library.toString())));
        candidate.setUpgrade(settings).setUpgradeMode(UpgradeMode.AUTOMATIC); ConfigUtil.sync(candidate);
        repository=new UpgradeRepository(); service=new UpgradeCommitService(repository,new LibraryPathMappingService(),new SpaceReservationService());
        var policy=new QualityPolicyEngine.Policy(); policy.setId("policy"); repository.save("upgrade_policies","policy",null,null,"ACTIVE",1,policy);
        target=new Target(); target.setId("target"); target.setSubjectId("100"); target.setLibraryId("lib"); target.setOutputRoot(library.toString());
        repository.save("upgrade_targets",target.getId(),"lib","100:lib","MONITORING",1,target);
        Release attrs=new ReleaseAnalysisService().parse("[A] Example - 01 [1080p][CHS][MKV]","100","fixture");
        source=Files.writeString(temporary.resolve("new.mkv"),"verified new fixture");
        oldFile=Files.writeString(library.resolve("old.mkv"),"existing old fixture");
        untouched=Files.writeString(library.resolve("episode-02.mkv"),"must remain untouched");
        old=new Version(); old.setId("old"); old.setPath(oldFile.toString()); old.setTargetId("target"); old.setAttributes(ani.rss.commons.GsonStatic.fromJson(ani.rss.commons.GsonStatic.toJson(attrs),Release.class));
        old.setSize(Files.size(oldFile)); old.setModifiedAt(Files.getLastModifiedTime(oldFile).toMillis());
        repository.save("upgrade_versions",old.getId(),"target",null,"ACTIVE",1,old);
        job=new Job(); job.setId("job"); job.setTargetId("target"); job.setIdentityKey("fixture:job"); job.setPolicyId("policy"); job.setPolicyRevision(1);
        job.setGeneration(ConfigUtil.snapshot().getUpgradeModeRevision()); job.setState(JobState.VERIFYING);
        JobFile install=new JobFile(); install.setSource(source.toString()); install.setDestination(library.resolve("new.mkv").toString()); install.setAttributes(attrs);
        install.setSize(Files.size(source)); install.setSha256(MediaVerificationService.sha256(source)); incoming=List.of(install);
        torrents=mockStatic(TorrentUtil.class);
        DownloaderClient client=mock(DownloaderClient.class); when(client.inventory()).thenReturn(DownloaderResult.success(List.of())); torrents.when(TorrentUtil::client).thenReturn(client);
    }
    @AfterEach void close() {
        torrents.close(); original.setUpgradeModeRevision(ConfigUtil.snapshot().getUpgradeModeRevision()); ConfigUtil.sync(original);
        DatabaseManager.close(); if(oldConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",oldConfig);
        ani.rss.util.basic.LogUtil.loadLogback();
    }
    @Test void installsBeforeDeletingOneExactOldFileAndRetainsUnrelatedEpisode() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        assertTrue(Files.exists(oldFile)); assertEquals(JobState.READY_TO_COMMIT,job.getState());
        service.commit(job,target);
        assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile));
        assertEquals("verified new fixture",Files.readString(temporary.resolve("library/new.mkv")));
        assertEquals("must remain untouched",Files.readString(untouched)); assertTrue(Files.exists(source));
        assertEquals("RETIRED",repository.counts("upgrade_versions").keySet().stream().filter("RETIRED"::equals).findFirst().orElseThrow());
        assertTrue(new ChapterCommitLockService().list(job.getId()).isEmpty());
    }
    @Test void equalEpisodeNumberCannotReplaceAnotherContentEdition() {
        incoming.get(0).getAttributes().setContentEdition("DIRECTORS_CUT");
        assertThrows(java.io.IOException.class,()->service.prepare(job,target,incoming,List.of(old)));
        assertTrue(Files.exists(oldFile)); assertFalse(Files.exists(Path.of(incoming.get(0).getDestination())));
    }
    @Test void moveCompletedBeforeDatabaseCommitRecoversByHashWithoutASecondInstall() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        JobFile file=repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).findFirst().orElseThrow();
        Files.copy(source,Path.of(file.getDestination())); job.setState(JobState.INSTALLING); service.saveJob(job);
        service.commit(job,target); assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile));
        assertEquals(1,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
    }
    @Test void incompleteJournalledCopyIsRecoveredOnTheNextAttempt() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        JobFile file=repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).findFirst().orElseThrow();
        Files.writeString(Path.of(file.getTemporary()),"partial"); file.setState("COPY_INCOMPLETE");
        repository.save("upgrade_job_files",file.getId(),job.getId(),null,file.getState(),1,file);
        assertTrue(Files.exists(oldFile)); service.commit(job,target); assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile));
        assertEquals("verified new fixture",Files.readString(Path.of(file.getDestination())));
    }
    @Test void interruptedCrossVolumeCopyIsJournalledAndConvergesAfterDatabaseRestart() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        JobFile install=repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).findFirst().orElseThrow();
        Path partial=Path.of(install.getTemporary());
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var filesystem=mockStatic(Files.class,invocation->armed.get()?invocation.callRealMethod():RETURNS_DEFAULTS.answer(invocation))) {
            filesystem.when(()->Files.createLink(partial,source)).thenThrow(new FileSystemException(partial.toString(),source.toString(),"cross-device link"));
            filesystem.when(()->Files.newOutputStream(partial,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)).thenAnswer(invocation->
                    new java.io.FilterOutputStream((java.io.OutputStream)invocation.callRealMethod()) {
                        @Override public void write(byte[] bytes,int offset,int length) throws java.io.IOException {
                            out.write(bytes,offset,Math.min(7,length)); throw new java.io.IOException("copy interrupted");
                        }
                    });
            armed.set(true);
            assertEquals("INSTALL_COPY_INCOMPLETE",assertThrows(java.io.IOException.class,()->service.commit(job,target)).getMessage());
        }
        assertEquals("COPY_INCOMPLETE",repository.get("upgrade_job_files",install.getId(),JobFile.class).orElseThrow().getState());
        assertEquals(JobState.INSTALLING,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertEquals("existing old fixture",Files.readString(oldFile)); assertEquals("must remain untouched",Files.readString(untouched));
        assertTrue(Files.size(partial)<Files.size(source)); assertFalse(Files.exists(Path.of(install.getDestination())));
        DatabaseManager.reopen(); service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target);
        assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertEquals(MediaVerificationService.sha256(source),MediaVerificationService.sha256(Path.of(install.getDestination())));
        assertFalse(Files.exists(oldFile)); assertFalse(Files.exists(partial)); assertEquals("must remain untouched",Files.readString(untouched));
        assertNull(repository.get("upgrade_job_files",install.getId(),JobFile.class).orElseThrow().getError());
    }
    @Test void aTemporaryPathClaimedBeforeCopyIsNotJournalledAsOwnedOrDeleted() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        JobFile install=repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).findFirst().orElseThrow();
        Path partial=Path.of(install.getTemporary());
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var filesystem=mockStatic(Files.class,invocation->armed.get()?invocation.callRealMethod():RETURNS_DEFAULTS.answer(invocation))) {
            filesystem.when(()->Files.createLink(partial,source)).thenAnswer(invocation->{
                Files.writeString(partial,"another owner"); throw new FileAlreadyExistsException(partial.toString());
            });
            armed.set(true);
            assertThrows(FileAlreadyExistsException.class,()->service.commit(job,target));
        }
        assertEquals("PLANNED",repository.get("upgrade_job_files",install.getId(),JobFile.class).orElseThrow().getState());
        assertEquals("another owner",Files.readString(partial)); assertTrue(Files.exists(oldFile)); assertFalse(Files.exists(Path.of(install.getDestination())));
        DatabaseManager.reopen();
        assertEquals("INSTALL_TEMPORARY_IDENTITY_CONFLICT",assertThrows(java.io.IOException.class,()->service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target)).getMessage());
        assertEquals("another owner",Files.readString(partial));
        assertEquals("PLANNED",repository.get("upgrade_job_files",install.getId(),JobFile.class).orElseThrow().getState());
        assertTrue(Files.exists(oldFile));
    }
    @Test void insufficientSpaceKeepsOldAndSourceAndRecoveryCanRetryAfterSpaceReturns() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); SpaceReservationService unavailable=mock(SpaceReservationService.class);
        doThrow(new java.io.IOException("INSUFFICIENT_DISK_SPACE")).when(unavailable).reserve(anyString(),any(Path.class),anyLong(),anyLong());
        service=new UpgradeCommitService(repository,new LibraryPathMappingService(),unavailable);
        assertEquals("INSUFFICIENT_DISK_SPACE",assertThrows(java.io.IOException.class,()->service.commit(job,target)).getMessage());
        assertEquals("existing old fixture",Files.readString(oldFile)); assertEquals("verified new fixture",Files.readString(source));
        assertFalse(Files.exists(Path.of(incoming.get(0).getDestination()))); assertNotEquals(JobState.COMPLETED,job.getState());
        DatabaseManager.reopen(); service=new UpgradeCommitService(repository,new LibraryPathMappingService(),new SpaceReservationService());
        service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target);
        assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertFalse(Files.exists(oldFile)); assertEquals("must remain untouched",Files.readString(untouched));
    }
    @Test void partialOldDeletionAndPermissionFailureRecoverOnlyTheRemainingFile() throws Exception {
        Path oldSecond=Files.writeString(oldFile.resolveSibling("old-second.mkv"),"existing old second fixture");
        Version second=new Version(); second.setId("old-second"); second.setTargetId(target.getId()); second.setPath(oldSecond.toString());
        Release secondAttributes=new ReleaseAnalysisService().parse("[A] Example - 02 [1080p][CHS][MKV]","100","fixture");
        second.setAttributes(secondAttributes); second.setSize(Files.size(oldSecond)); second.setModifiedAt(Files.getLastModifiedTime(oldSecond).toMillis());
        repository.save("upgrade_versions",second.getId(),target.getId(),null,"ACTIVE",1,second);
        Path sourceSecond=Files.writeString(temporary.resolve("new-second.mkv"),"verified new second fixture");
        JobFile next=new JobFile(); next.setSource(sourceSecond.toString()); next.setDestination(oldSecond.resolveSibling("new-second.mkv").toString());
        next.setAttributes(secondAttributes); next.setSize(Files.size(sourceSecond)); next.setSha256(MediaVerificationService.sha256(sourceSecond));
        service.prepare(job,target,List.of(incoming.get(0),next),List.of(old,second));
        var deletes=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<Object> interrupted=invocation->{
            if(deletes.incrementAndGet()==1) return invocation.callRealMethod();
            throw new AccessDeniedException(invocation.getArgument(0).toString());
        };
        var armed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var filesystem=mockStatic(Files.class,invocation->armed.get()?invocation.callRealMethod():RETURNS_DEFAULTS.answer(invocation))) {
            filesystem.when(()->Files.delete(oldFile)).thenAnswer(interrupted); filesystem.when(()->Files.delete(oldSecond)).thenAnswer(interrupted);
            armed.set(true);
            assertThrows(AccessDeniedException.class,()->service.commit(job,target));
        }
        assertEquals(JobState.CLEANUP_PENDING,job.getState()); assertEquals(1,(Files.exists(oldFile)?1:0)+(Files.exists(oldSecond)?1:0));
        assertEquals(1,repository.all("upgrade_job_files",job.getId(),null,JobFile.class).stream().filter(f->"DELETE_OLD".equals(f.getAction())&&"DELETED".equals(f.getState())).count());
        assertEquals("verified new fixture",Files.readString(Path.of(incoming.get(0).getDestination())));
        assertEquals("verified new second fixture",Files.readString(Path.of(next.getDestination()))); assertEquals("must remain untouched",Files.readString(untouched));
        DatabaseManager.reopen(); service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target);
        assertFalse(Files.exists(oldFile)); assertFalse(Files.exists(oldSecond));
        assertEquals(2,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
        assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertEquals("must remain untouched",Files.readString(untouched)); assertTrue(Files.exists(source)); assertTrue(Files.exists(sourceSecond));
    }
    @Test void hardLinkInstallationAndExactOldUnlinkLeaveOtherLinksValid() throws Exception {
        Path sharedOld=Files.createLink(temporary.resolve("shared-old.mkv"),oldFile);
        assertTrue(Files.isSameFile(sharedOld,oldFile)); String oldHash=MediaVerificationService.sha256(sharedOld);
        service.prepare(job,target,incoming,List.of(old)); service.commit(job,target);
        assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile));
        assertEquals(oldHash,MediaVerificationService.sha256(sharedOld)); assertTrue(Files.isSameFile(source,Path.of(incoming.get(0).getDestination())));
        assertEquals("must remain untouched",Files.readString(untouched));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void failedEmbyRefreshRetriesMetadataWithoutRepeatingCommittedMediaChanges(boolean outsideDefaultRoot) throws Exception {
        var reject=new java.util.concurrent.atomic.AtomicBoolean(true); var requests=Collections.synchronizedList(new ArrayList<String>());
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            requests.add(exchange.getRequestURI()+" "+body);
            if(exchange.getRequestURI().getPath().endsWith("/Refresh")&&reject.get()) {
                byte[] error="refresh unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(503,error.length); try(var out=exchange.getResponseBody()) { out.write(error); }
            } else { exchange.sendResponseHeaders(204,-1); exchange.close(); }
        }); server.start();
        try {
            ConfigUtil.CONFIG.getUpgrade().setEmbyUrl("http://127.0.0.1:"+server.getAddress().getPort()); ConfigUtil.CONFIG.getUpgrade().setEmbyApiKey("test-only");
            target.setSeason(1); target.setTitle("Official title"); target.setEmbySeriesId("series");
            if(outsideDefaultRoot) {
                target.setOutputDirectory(oldFile.getParent().toString()); target.setOutputRoot(Files.createDirectory(temporary.resolve("default-root")).toString());
                ConfigUtil.CONFIG.getUpgrade().setTvRoot(target.getOutputRoot()); ConfigUtil.CONFIG.getUpgrade().setMovieRoot(target.getOutputRoot());
            }
            repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib","MONITORING",1,target);
            Path nfo=oldFile.getParent().resolve("season.nfo"); Files.writeString(nfo,"<season><title>User title</title></season>");
            service.prepare(job,target,incoming,List.of(old)); service.commit(job,target);
            List<String> versions=repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).stream().map(Version::getId).toList();
            var emby=new EmbyLibraryIndexService(repository,new LibraryPathMappingService(),new ReleaseAnalysisService());
            var metadata=new MetadataEnrichmentService(repository,new LibraryPathMappingService(),emby);
            var scheduler=new UpgradeScheduler(repository,null,null,emby,null,null,null,null,service,null,null,null,metadata,null);
            Job operation=new Job(); operation.setId("metadata"); operation.setKind("METADATA"); operation.setTargetId(target.getId()); operation.setState(JobState.QUEUED); service.saveJob(operation);
            assertEquals("METADATA_REFRESH_DEFERRED",assertThrows(java.io.IOException.class,()->scheduler.process(operation)).getMessage());
            assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState()); assertFalse(Files.exists(oldFile));
            assertEquals(MediaVerificationService.sha256(source),MediaVerificationService.sha256(Path.of(incoming.get(0).getDestination()))); assertEquals("must remain untouched",Files.readString(untouched));
            assertTrue(Files.readString(nfo).contains("User title")); String nfoHash=MediaVerificationService.sha256(nfo);
            reject.set(false); DatabaseManager.reopen(); scheduler.process(repository.get("upgrade_jobs",operation.getId(),Job.class).orElseThrow());
            assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",operation.getId(),Job.class).orElseThrow().getState());
            assertEquals(versions,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).stream().map(Version::getId).toList());
            assertEquals(nfoHash,MediaVerificationService.sha256(nfo)); assertEquals("must remain untouched",Files.readString(untouched));
            assertTrue(requests.stream().anyMatch(request->request.contains("/Library/Media/Updated")&&request.contains("/media/new.mkv")&&request.contains("/media/old.mkv")&&request.contains("Deleted")));
            assertTrue(requests.stream().anyMatch(request->request.contains("/Items/series/Refresh")&&request.contains("ReplaceAllMetadata=false")&&request.contains("ReplaceAllImages=false")&&request.contains("Recursive=false")));
        } finally { server.stop(0); }
    }
    @Test void downloaderStopFailureKeepsOldAndNewUntilCleanupRetry() throws Exception {
        old.setDownloadHash("b".repeat(40)); repository.save("upgrade_versions",old.getId(),target.getId(),null,"ACTIVE",1,old);
        var task=new ani.rss.entity.torrent.TorrentsInfo().setId("old-task").setHash(old.getDownloadHash());
        DownloaderClient client=mock(DownloaderClient.class); torrents.when(TorrentUtil::client).thenReturn(client);
        when(client.inventory()).thenReturn(DownloaderResult.success(List.of(task))); when(client.delete(task,false)).thenReturn(DownloaderResult.failed("PAUSE_FAILED",true));
        service.prepare(job,target,incoming,List.of(old)); assertThrows(java.io.IOException.class,()->service.commit(job,target));
        assertEquals(JobState.CLEANUP_PENDING,job.getState()); assertTrue(Files.exists(oldFile)); assertTrue(Files.exists(Path.of(incoming.get(0).getDestination())));
        assertFalse(new ChapterCommitLockService().list(job.getId()).isEmpty());
        when(client.delete(task,false)).thenReturn(DownloaderResult.success(null)); service.commit(job,target);
        assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile)); verify(client,never()).delete(task,true);
    }
    @Test void externallyModifiedOldFileBecomesCleanupPendingWithNewVersionAvailable() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); Files.writeString(oldFile,"external replacement");
        assertThrows(java.io.IOException.class,()->service.commit(job,target));
        assertEquals(JobState.CLEANUP_PENDING,job.getState()); assertEquals("external replacement",Files.readString(oldFile));
        assertTrue(Files.exists(temporary.resolve("library/new.mkv")));
    }
    @Test void incompleteTemporaryCopyKeepsOldAndDoesNotReportSuccess() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        JobFile install=repository.list("upgrade_job_files","job",null,0,200,JobFile.class).stream().filter(f->"INSTALL".equals(f.getAction())).findFirst().orElseThrow();
        Files.writeString(Path.of(install.getTemporary()),"partial");
        assertThrows(java.io.IOException.class,()->service.commit(job,target));
        assertTrue(Files.exists(oldFile)); assertFalse(Files.exists(temporary.resolve("library/new.mkv"))); assertNotEquals(JobState.COMPLETED,job.getState());
    }
    private static class SimulatedProcessExit extends Error {}
    @ParameterizedTest @EnumSource(value=JobState.class,names={"READY_TO_COMMIT","INSTALLING","INSTALLED","CLEANING_OLD","CLEANUP_PENDING"})
    void everyCommitStateBoundaryRetainsAValidVersionAndRecoversAfterDatabaseRestart(JobState boundary) throws Exception {
        UpgradeRepository interrupted=spy(repository);
        doAnswer(invocation->{
            invocation.callRealMethod();
            if(boundary!=JobState.INSTALLED&&"upgrade_jobs".equals(invocation.getArgument(0))&&boundary.name().equals(invocation.getArgument(4))) throw new SimulatedProcessExit();
            return null;
        }).when(interrupted).save(anyString(),anyString(),any(),any(),anyString(),anyLong(),any());
        doAnswer(invocation->{ invocation.callRealMethod(); if(boundary==JobState.INSTALLED&&"INSTALLED".equals(invocation.getArgument(1))) throw new SimulatedProcessExit(); return null; })
                .when(interrupted).event(anyString(),anyString(),anyString());
        service=new UpgradeCommitService(interrupted,new LibraryPathMappingService(),new SpaceReservationService());
        if(boundary==JobState.READY_TO_COMMIT) assertThrows(SimulatedProcessExit.class,()->service.prepare(job,target,incoming,List.of(old)));
        else {
            service.prepare(job,target,incoming,List.of(old));
            if(boundary==JobState.CLEANUP_PENDING) { old.setProtectedVersion(true); repository.save("upgrade_versions",old.getId(),target.getId(),null,"ACTIVE",1,old); }
            assertThrows(SimulatedProcessExit.class,()->service.commit(job,target));
        }
        Path installed=Path.of(incoming.get(0).getDestination());
        assertTrue(Files.exists(oldFile)||Files.exists(installed));
        if(Files.exists(oldFile)) assertEquals("existing old fixture",Files.readString(oldFile));
        if(Files.exists(installed)) assertEquals(MediaVerificationService.sha256(source),MediaVerificationService.sha256(installed));
        assertEquals("must remain untouched",Files.readString(untouched));
        DatabaseManager.reopen(); assertEquals(boundary,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        if(boundary==JobState.CLEANUP_PENDING) { old.setProtectedVersion(false); repository.save("upgrade_versions",old.getId(),target.getId(),null,"ACTIVE",1,old); }
        service=new UpgradeCommitService(repository,new LibraryPathMappingService(),new SpaceReservationService());
        service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target);
        assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertFalse(Files.exists(oldFile)); assertEquals(MediaVerificationService.sha256(source),MediaVerificationService.sha256(installed));
        assertEquals(1,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size()); assertEquals("must remain untouched",Files.readString(untouched));
        assertTrue(new ChapterCommitLockService().list(job.getId()).isEmpty());
    }
    @Test void aProcessExitAfterOldUnlinkBeforeJournalSaveRetiresTheMissingFileOnRestart() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); var armed=new java.util.concurrent.atomic.AtomicBoolean();
        try(var filesystem=mockStatic(Files.class,invocation->armed.get()?invocation.callRealMethod():RETURNS_DEFAULTS.answer(invocation))) {
            filesystem.when(()->Files.delete(oldFile)).thenAnswer(invocation->{ invocation.callRealMethod(); throw new SimulatedProcessExit(); }); armed.set(true);
            assertThrows(SimulatedProcessExit.class,()->service.commit(job,target));
        }
        assertFalse(Files.exists(oldFile)); assertEquals(JobState.CLEANING_OLD,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertEquals("verified new fixture",Files.readString(Path.of(incoming.get(0).getDestination()))); assertEquals("must remain untouched",Files.readString(untouched));
        DatabaseManager.reopen(); service.commit(repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow(),target);
        assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs",job.getId(),Job.class).orElseThrow().getState());
        assertEquals(1,repository.all("upgrade_versions",target.getId(),"ACTIVE",Version.class).size());
        assertEquals(1,repository.all("upgrade_versions",target.getId(),"RETIRED",Version.class).size()); assertEquals("must remain untouched",Files.readString(untouched));
    }
    @Test void modeSwitchBeforeCommitCannotDeleteOld() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); var c=ConfigUtil.snapshot(); c.setUpgradeMode(UpgradeMode.OFF); ConfigUtil.sync(c);
        assertThrows(java.io.IOException.class,()->service.commit(job,target)); assertTrue(Files.exists(oldFile));
    }
    @Test void installingRecoveryConvergesAfterModeAndPolicyChange() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); job.setState(JobState.INSTALLING); service.saveJob(job);
        var c=ConfigUtil.snapshot(); c.setUpgradeMode(UpgradeMode.OFF); ConfigUtil.sync(c);
        var changed=new QualityPolicyEngine.Policy(); changed.setId("policy"); changed.setRevision(2); repository.save("upgrade_policies","policy",null,null,"ACTIVE",2,changed);
        service.commit(job,target); assertEquals(JobState.COMPLETED,job.getState()); assertFalse(Files.exists(oldFile));
    }
    @Test void protectionAddedAfterPrepareKeepsOldWhileNewRemainsAvailable() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); old.setProtectedVersion(true); repository.save("upgrade_versions",old.getId(),"target",null,"ACTIVE",1,old);
        assertThrows(java.io.IOException.class,()->service.commit(job,target)); assertEquals(JobState.CLEANUP_PENDING,job.getState());
        assertTrue(Files.exists(oldFile)); assertTrue(Files.exists(Path.of(incoming.get(0).getDestination())));
    }
    @Test void cancellationCannotBeLostByAnInFlightJobSaveOrReachInstallation() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); repository.requestCancellation(job.getId());
        job.setState(JobState.READY_TO_COMMIT); service.saveJob(job); assertEquals(JobState.CANCELLED,job.getState());
        assertThrows(java.io.IOException.class,()->service.commit(job,target)); assertTrue(Files.exists(oldFile)); assertFalse(Files.exists(Path.of(incoming.get(0).getDestination())));
    }
    @Test void exclusiveOldSubtitleIsDeletedButSharedSubtitleIsRetained() throws Exception {
        Path exclusive=Files.writeString(oldFile.resolveSibling("old.chs.ass"),"exclusive subtitles"); Path shared=Files.writeString(oldFile.resolveSibling("shared.ass"),"shared subtitles");
        for(Path file:List.of(exclusive,shared)) {
            Version subtitle=new Version(); subtitle.setId(file.getFileName().toString()); subtitle.setTargetId("target"); subtitle.setPath(file.toString()); subtitle.setKind("SUBTITLE"); subtitle.setAttributes(old.getAttributes());
            subtitle.setSize(Files.size(file)); subtitle.setModifiedAt(Files.getLastModifiedTime(file).toMillis()); subtitle.getRelatedVersionIds().add(old.getId());
            if(file.equals(shared)) subtitle.getRelatedVersionIds().add("unreplaced-video"); repository.save("upgrade_versions",subtitle.getId(),"target",null,"ACTIVE",1,subtitle);
        }
        service.prepare(job,target,incoming,List.of(old)); service.commit(job,target);
        assertFalse(Files.exists(oldFile)); assertFalse(Files.exists(exclusive)); assertTrue(Files.exists(shared));
    }
    @Test void partialCoverageAndDestinationCollisionAreRejectedBeforeInstallation() throws Exception {
        old.getAttributes().getEpisodes().add("2");
        assertThrows(java.io.IOException.class,()->service.prepare(job,target,incoming,List.of(old)));
        old.getAttributes().getEpisodes().remove("2"); Files.writeString(Path.of(incoming.get(0).getDestination()),"unrelated owner");
        assertThrows(java.io.IOException.class,()->service.prepare(job,target,incoming,List.of(old))); assertTrue(Files.exists(oldFile));
    }
    @Test void aNewOverlappingBaselineInvalidatesThePreparedDecisionBeforeAnyInstall() throws Exception {
        service.prepare(job,target,incoming,List.of(old));
        Version better=new Version(); better.setId("better"); better.setTargetId(target.getId()); better.setPath(untouched.toString());
        better.setAttributes(ani.rss.commons.GsonStatic.fromJson(ani.rss.commons.GsonStatic.toJson(old.getAttributes()),Release.class)); better.getAttributes().setResolution(2160);
        repository.save("upgrade_versions",better.getId(),target.getId(),null,"ACTIVE",1,better);
        assertEquals("BASELINE_CHANGED",assertThrows(java.io.IOException.class,()->service.commit(job,target)).getMessage());
        assertEquals(JobState.VERIFYING,job.getState()); assertTrue(Files.exists(oldFile)); assertTrue(Files.exists(untouched));
        assertFalse(Files.exists(Path.of(incoming.get(0).getDestination()))); assertTrue(new ChapterCommitLockService().list(job.getId()).isEmpty());
    }
    @Test void anUnrelatedChapterAndRefreshedEvidenceDoNotInvalidateThePreparedDecision() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); old.getAttributes().getEvidence().add(new Evidence("scan","title","refreshed"));
        repository.save("upgrade_versions",old.getId(),target.getId(),null,"ACTIVE",1,old);
        Version unrelated=new Version(); unrelated.setId("other"); unrelated.setTargetId(target.getId()); unrelated.setPath(untouched.toString());
        unrelated.setAttributes(new ReleaseAnalysisService().parse("Example - 02 [1080p]","100","fixture")); repository.save("upgrade_versions",unrelated.getId(),target.getId(),null,"ACTIVE",1,unrelated);
        service.commit(job,target); assertEquals(JobState.COMPLETED,job.getState()); assertTrue(Files.exists(untouched));
    }
    @Test void aPersistentRecoveryClaimBlocksOrdinarySubmissionsAfterAutomaticModeIsOff() throws Exception {
        service.prepare(job,target,incoming,List.of(old)); job.setState(JobState.INSTALLING); service.saveJob(job);
        assertTrue(new ChapterCommitLockService().acquire(job,target,incoming,0));
        var config=ConfigUtil.snapshot(); config.setUpgradeMode(UpgradeMode.OFF); ConfigUtil.sync(config);
        var subscription=new ani.rss.entity.Ani().setTitle("Example").setBgmUrl("https://bgm.tv/subject/100").setReleaseDate(new Date()).setOva(false).setSeason(1)
                .setCustomDownloadPath(true).setCustomDownloadPathTemplate(target.getOutputRoot());
        assertTrue(UpgradeFileCoordinator.persistentConflict(subscription,null)); assertTrue(UpgradeFileCoordinator.managed(subscription));
        assertFalse(new ani.rss.service.DownloadService().recoverDownload(subscription,null,null,null));
        Release other=new Release(); other.getEpisodes().add("2"); assertFalse(UpgradeFileCoordinator.persistentConflict(subscription,other));
        new ChapterCommitLockService().release(job.getId()); assertFalse(UpgradeFileCoordinator.managed(subscription));
    }
    @Test void anExistingArchiveOutsideTheDefaultRootStillParticipatesInThePersistentConflictCheck() throws Exception {
        target.setOutputDirectory(Files.createDirectory(temporary.resolve("existing-archive")).toString());
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib","MONITORING",1,target);
        job.setState(JobState.INSTALLING); service.saveJob(job); assertTrue(new ChapterCommitLockService().acquire(job,target,incoming,0));
        var config=ConfigUtil.snapshot(); config.setUpgradeMode(UpgradeMode.OFF); ConfigUtil.sync(config);
        var subscription=new ani.rss.entity.Ani().setTitle("Example").setBgmUrl("https://bgm.tv/subject/100").setReleaseDate(new Date()).setOva(false).setSeason(1)
                .setCustomDownloadPath(true).setCustomDownloadPathTemplate(target.getOutputDirectory());
        assertTrue(UpgradeFileCoordinator.managed(subscription));
        subscription.setCustomDownloadPathTemplate(temporary.resolve("existing-archive-other").toString()); assertFalse(UpgradeFileCoordinator.managed(subscription));
    }
}
