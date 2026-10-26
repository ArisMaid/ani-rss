package ani.rss.upgrade;

import ani.rss.controller.v2.UpgradeController;
import ani.rss.entity.Config;
import ani.rss.persistence.DatabaseManager;
import ani.rss.upgrade.UpgradeModels.*;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeCampaignServiceTest {
    @Test void ordinarySubscriptionReferenceAlsoPreventsPolicyDeletion() {
        var subscription=new ani.rss.entity.Ani().setId("ordinary-sub").setTitle("Example").setQualityPolicyId("task");
        ani.rss.util.other.AniUtil.ANI_LIST.add(subscription);
        try { assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",1)); }
        finally { ani.rss.util.other.AniUtil.ANI_LIST.remove(subscription); }
    }
    @Test void canCreateMonitorOnlyCampaignWithoutAnEmbyLibrary() {
        ConfigUtil.CONFIG.getUpgrade().setLibraryIds(List.of());
        Campaign campaign=new Campaign(); campaign.setName("2026 夏季监测"); campaign.setStartDate("2026-07-01"); campaign.setEndDate("2026-09-30");
        Campaign saved=service.saveCampaign(null,campaign);
        assertEquals("UNASSIGNED",saved.getLibraryId());
        assertTrue(saved.isEnabled());
    }
    @TempDir Path directory;
    private String previousConfig;
    private Config original;
    private UpgradeRepository repository;
    private UpgradeCampaignService service;
    private Target target;

    @BeforeEach void before() {
        previousConfig=System.getProperty("CONFIG"); System.setProperty("CONFIG",directory.resolve("config").toString()); DatabaseManager.close(); original=ConfigUtil.snapshot();
        var settings=new UpgradeSettings(); settings.setLibraryIds(List.of("lib")); settings.setDefaultPolicyId("global"); ConfigUtil.CONFIG.setUpgrade(settings);
        repository=new UpgradeRepository(); service=new UpgradeCampaignService(repository,new QualityPolicyEngine());
        policy("global",List.of(QualityPolicyEngine.Dimension.RESOLUTION),720); policy("task",List.of(QualityPolicyEngine.Dimension.GROUP),1080);
        policy("override",List.of(QualityPolicyEngine.Dimension.SUBTITLES),2160);
        target=new Target(); target.setId("target"); target.setLibraryId("lib"); target.setSubjectId("100"); target.setPremiereDate("2024-04-01");
    }
    @AfterEach void after() {
        DatabaseManager.close(); BeanUtil.copyProperties(original,ConfigUtil.CONFIG);
        if(previousConfig==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",previousConfig);
    }
    private void policy(String id,List<QualityPolicyEngine.Dimension> dimensions,int minimum) {
        var policy=new QualityPolicyEngine.Policy(); policy.setId(id); policy.setName(id); policy.setDimensions(dimensions); policy.setMinimumResolution(minimum);
        repository.save("upgrade_policies",id,null,null,"ACTIVE",policy.getRevision(),policy);
    }
    private Campaign draft(String policy) {
        Campaign value=new Campaign(); value.setName("date task"); value.setLibraryId("lib"); value.setPolicyId(policy); value.setStartDate("2024-04-01"); value.setEndDate("2024-04-30"); return value;
    }
    private Campaign campaign(String id,String policy,int priority,long created) {
        Campaign value=draft(policy); value.setId(id); value.setPriority(priority); value.setCreatedAt(created); save(value); return value;
    }
    private void save(Campaign value) { repository.save("upgrade_campaigns",value.getId(),null,null,value.isEnabled()?"ENABLED":"PAUSED",value.getRevision(),value); }
    @Test void anUnspecifiedTaskRuleUsesTheCurrentSavedGlobalRuleAfterRestart() {
        Campaign saved=service.saveCampaign(null,draft("  ")); assertNull(saved.getPolicyId());
        DatabaseManager.reopen(); assertEquals("global",service.effectivePolicy(target).getId()); assertEquals(saved.getId(),target.getCampaignId()); assertTrue(service.eligible(target));
        ConfigUtil.CONFIG.getUpgrade().setDefaultPolicyId("task"); assertEquals("task",service.effectivePolicy(target).getId());
        assertNull(repository.get("upgrade_campaigns",saved.getId(),Campaign.class).orElseThrow().getPolicyId());
    }
    @Test void completeTaskAndSubjectRulesOverrideWithoutMergingGlobalFields() {
        service.saveCampaign(null,draft("task"));
        var selected=service.effectivePolicy(target); assertEquals("task",selected.getId()); assertEquals(1080,selected.getMinimumResolution()); assertEquals(List.of(QualityPolicyEngine.Dimension.GROUP),selected.getDimensions());
        target.setPolicyOverride("override"); selected=service.effectivePolicy(target);
        assertEquals("override",selected.getId()); assertEquals(2160,selected.getMinimumResolution()); assertEquals(List.of(QualityPolicyEngine.Dimension.SUBTITLES),selected.getDimensions());
        target.setPolicyOverride(null); assertEquals("task",service.effectivePolicy(target).getId());
    }
    @Test void aSubjectOverrideAlsoWinsWhenTheTaskInheritsTheGlobalRule() {
        service.saveCampaign(null,draft(null)); target.setPolicyOverride("override"); assertEquals("override",service.effectivePolicy(target).getId());
        ConfigUtil.CONFIG.getUpgrade().setDefaultPolicyId(null); assertEquals("override",service.effectivePolicy(target).getId());
    }
    @Test void overlappingCampaignsChoosePriorityThenCreationTimeThenAStableTie() {
        campaign("low","global",0,10); campaign("recent","task",10,30); Campaign first=campaign("first","override",10,20);
        assertEquals("override",service.effectivePolicy(target).getId()); assertEquals("first",target.getCampaignId());
        first.setEnabled(false); save(first); assertEquals("task",service.effectivePolicy(target).getId());
        campaign("a","global",20,40); campaign("b","override",20,40); assertEquals("global",service.effectivePolicy(target).getId()); assertEquals("a",target.getCampaignId());
    }
    @Test void datesRequireTheEntirePremiereRangeAndTheSelectedLibrary() {
        Campaign selected=campaign("selected","task",10,20); selected.setEndDate("2024-04-15"); save(selected);
        target.setPremiereUpper("2024-04-30"); assertFalse(service.eligible(target)); assertEquals("global",service.effectivePolicy(target).getId()); assertNull(target.getCampaignId());
        target.setPremiereUpper("2024-04-15"); assertTrue(service.eligible(target));
        target.setLibraryId("another-library"); assertFalse(service.eligible(target));
    }
    @Test void missingOrInvalidDefaultsAndExplicitRulesAreRejectedBeforeSaving() {
        ConfigUtil.CONFIG.getUpgrade().setDefaultPolicyId(null); assertThrows(IllegalArgumentException.class,()->service.saveCampaign(null,draft(null)));
        ConfigUtil.CONFIG.getUpgrade().setDefaultPolicyId("missing"); assertThrows(IllegalArgumentException.class,()->service.saveCampaign(null,draft(null)));
        ConfigUtil.CONFIG.getUpgrade().setDefaultPolicyId("global"); assertThrows(IllegalArgumentException.class,()->service.saveCampaign(null,draft("missing")));
        assertTrue(repository.counts("upgrade_campaigns").isEmpty());
        target.setPolicyOverride("missing"); assertThrows(NoSuchElementException.class,()->service.effectivePolicy(target));
    }
    @Test void targetReadRecomputesCurrentSelectionWithoutWritingArchivesOrQueues() {
        Campaign active=campaign("active","task",10,20); target.setCampaignId("stale");
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib",target.getState(),target.getRevision(),target);
        var controller=new UpgradeController(repository,null,service,null,null,null,null,null,null);
        assertEquals("active",controller.targets(0,50).items().get(0).getCampaignId());
        active.setEnabled(false); save(active); assertNull(controller.targets(0,50).items().get(0).getCampaignId());
        assertEquals("stale",repository.get("upgrade_targets",target.getId(),Target.class).orElseThrow().getCampaignId()); assertTrue(repository.counts("upgrade_jobs").isEmpty());
        active.setEnabled(true); save(active); target.setExcluded(true);
        repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib",target.getState(),target.getRevision(),target);
        assertNull(controller.targets(0,50).items().get(0).getCampaignId()); assertFalse(service.eligible(target));
    }
    @Test void changingToDefaultRulePreservesCreationTimeAndRejectsStaleRevisions() {
        Campaign saved=service.saveCampaign(null,draft("task")); long created=saved.getCreatedAt();
        Campaign stale=repository.get("upgrade_campaigns",saved.getId(),Campaign.class).orElseThrow(); saved.setPolicyId(null); saved=service.saveCampaign(saved.getId(),saved);
        assertEquals(2,saved.getRevision()); assertEquals(created,saved.getCreatedAt()); assertNull(saved.getPolicyId());
        assertThrows(ani.rss.exception.ApiProblemException.class,()->service.saveCampaign(stale.getId(),stale)); assertEquals("global",service.effectivePolicy(target).getId());
    }
    @Test void savedRuleEditsKeepIdentityAndPersistEveryOrderingAfterRestart() {
        var edited=repository.get("upgrade_policies","task",QualityPolicyEngine.Policy.class).orElseThrow();
        edited.setName("edited rule"); edited.setDimensions(List.of(QualityPolicyEngine.Dimension.RESOLUTION,QualityPolicyEngine.Dimension.SUBTITLES,QualityPolicyEngine.Dimension.GROUP));
        edited.setSubtitles(List.of("CHS","CHS+CHT")); edited.setGroups(List.of("B","A")); edited.setResolutions(List.of(1080,2160,720));
        edited.setSources(List.of("WEB","BD")); edited.setPackaging(List.of("HARD","SOFT")); edited.setCodecs(List.of("HEVC","AV1")); edited.setMinimumResolution(720);
        edited.setGroupAliases(Map.of("B alias","B")); service.savePolicy("task",edited); DatabaseManager.reopen();
        var saved=repository.get("upgrade_policies","task",QualityPolicyEngine.Policy.class).orElseThrow();
        assertEquals("task",saved.getId()); assertEquals(2,saved.getRevision()); assertEquals("edited rule",saved.getName());
        assertEquals(edited,saved); assertEquals(3,repository.counts("upgrade_policies").get("ACTIVE"));
    }
    @Test void deletingAnUnusedRuleHidesItButPreservesTheHistoricalDefinition() {
        service.deletePolicy("task",1); DatabaseManager.reopen();
        assertTrue(repository.get("upgrade_policies","task",QualityPolicyEngine.Policy.class).isEmpty());
        var controller=new UpgradeController(repository,null,service,null,null,null,null,null,null);
        assertEquals(Set.of("global","override"),controller.policies().stream().map(QualityPolicyEngine.Policy::getId).collect(java.util.stream.Collectors.toSet()));
        var archived=repository.all("upgrade_policies",null,"DELETED",QualityPolicyEngine.Policy.class).get(0);
        assertEquals("task",archived.getId()); assertEquals(2,archived.getRevision()); assertEquals(List.of(QualityPolicyEngine.Dimension.GROUP),archived.getDimensions());
        assertThrows(IllegalArgumentException.class,()->service.saveCampaign(null,draft("task")));
        assertThrows(IllegalArgumentException.class,()->service.savePolicy("task",archived));
        var missing=assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",2)); assertEquals(404,missing.status().value());
    }
    @Test void defaultsPausedCampaignsAndExcludedOverridesMustBeUnlinkedBeforeDeletion() {
        var defaultConflict=assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("global",1));
        assertEquals("UPGRADE_POLICY_IN_USE",defaultConflict.code()); assertEquals(409,defaultConflict.status().value());
        Campaign paused=campaign("paused","task",0,1); paused.setEnabled(false); save(paused);
        assertEquals("UPGRADE_POLICY_IN_USE",assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",1)).code());
        target.setExcluded(true); target.setPolicyOverride("override"); repository.save("upgrade_targets",target.getId(),target.getLibraryId(),"100:lib",target.getState(),1,target);
        assertEquals("UPGRADE_POLICY_IN_USE",assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("override",1)).code());
        assertTrue(repository.all("upgrade_policies",null,"DELETED",QualityPolicyEngine.Policy.class).isEmpty());
    }
    @Test void unfinishedExecutionAndPendingCancellationBlockDeletionButCompletedHistoryDoesNot() {
        Job job=new Job(); job.setId("job"); job.setPolicyId("task"); job.setState(JobState.CLEANUP_PENDING);
        repository.save("upgrade_jobs",job.getId(),"target",null,job.getState().name(),1,job);
        assertEquals("UPGRADE_POLICY_IN_USE",assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",1)).code());
        job.setState(JobState.CANCELLED); job.setReasonCode("CANCEL_STOP_PENDING"); repository.save("upgrade_jobs",job.getId(),"target",null,job.getState().name(),1,job);
        assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",1));
        job.setState(JobState.COMPLETED); job.setReasonCode(null); repository.save("upgrade_jobs",job.getId(),"target",null,job.getState().name(),1,job);
        service.deletePolicy("task",1); assertEquals(JobState.COMPLETED,repository.get("upgrade_jobs","job",Job.class).orElseThrow().getState());
    }
    @Test void aStaleDeleteRequestCannotRemoveANewlyEditedRule() {
        var policy=repository.get("upgrade_policies","task",QualityPolicyEngine.Policy.class).orElseThrow(); policy.setName("new name"); service.savePolicy("task",policy);
        assertEquals("UPGRADE_CONFIG_CONFLICT",assertThrows(ani.rss.exception.ApiProblemException.class,()->service.deletePolicy("task",1)).code());
        assertEquals("new name",repository.get("upgrade_policies","task",QualityPolicyEngine.Policy.class).orElseThrow().getName());
    }
}
