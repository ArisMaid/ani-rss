package ani.rss.upgrade;

import ani.rss.entity.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.upgrade.UpgradeModels.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UpgradeFollowupTest {
    @Test void aChineseTrackAfterEightEnglishTracksStillOverridesTheWrongSimplifiedDeclaration() throws Exception {
        var service=new MediaVerificationService(); org.junit.jupiter.api.Assumptions.assumeTrue(service.available("ffmpeg")&&service.available("ffprobe"));
        var english=java.nio.file.Files.writeString(directory.resolve("en.srt"),"1\n00:00:00,000 --> 00:00:03,000\nEnglish subtitle.\n");
        var chinese=java.nio.file.Files.writeString(directory.resolve("cht.srt"),"1\n00:00:00,000 --> 00:00:03,000\n這裡還有這麼多讓我們說不完的話。\n");
        var video=directory.resolve("many-tracks.mkv");
        List<String> args=new ArrayList<>(List.of("ffmpeg","-v","error","-f","lavfi","-i","color=s=1920x1080:r=5","-i",english.toString(),"-i",chinese.toString(),"-t","3","-map","0:v"));
        for(int i=0;i<8;i++) args.addAll(List.of("-map","1:s")); args.addAll(List.of("-map","2:s","-c:v","libx264","-threads","1","-c:s","srt"));
        for(int i=0;i<8;i++) args.addAll(List.of("-metadata:s:s:"+i,"language=eng")); args.addAll(List.of("-metadata:s:s:8","language=zho",video.toString()));
        MediaVerificationService.run(args,30,100000);
        var declared=new ReleaseAnalysisService().parse("[A] Example - 01 [1080p][CHS][内封][MKV]","100","TEST");
        var actual=service.verify(video,declared,new UpgradeSettings()).attributes();
        assertEquals(Set.of("CHT"),actual.getLanguages());
        var englishVideo=directory.resolve("english-only.mkv");
        MediaVerificationService.run(List.of("ffmpeg","-v","error","-f","lavfi","-i","color=s=1920x1080:r=5","-i",english.toString(),"-t","3","-map","0:v","-map","1:s","-c:v","libx264","-threads","1","-c:s","srt","-metadata:s:s:0","language=zho",englishVideo.toString()),30,100000);
        assertTrue(service.verify(englishVideo,declared,new UpgradeSettings()).attributes().getLanguages().isEmpty());
        var external=directory.resolve("Example.chs.srt"); java.nio.file.Files.copy(english,external);
        var externalClaim=new ReleaseAnalysisService().parse("Example - 01 [1080p][CHS][外挂][MKV]","100","TEST");
        service.supplementSubtitles(externalClaim,directory.resolve("Example.mkv"),List.of(external));
        assertTrue(externalClaim.getLanguages().isEmpty());
        var ass=directory.resolve("Example.chs.ass");
        java.nio.file.Files.writeString(ass,"[Script Info]\nTitle: 这们说发无开门风过进与书语爱见头车东\n[Events]\nDialogue: 0,0:00:00.00,0:00:03.00,Default,,0,0,0,,English subtitle only.\n");
        externalClaim.getLanguages().add("CHS"); service.supplementSubtitles(externalClaim,directory.resolve("Example.mkv"),List.of(ass));
        assertTrue(externalClaim.getLanguages().isEmpty(),"ASS headers are not dialogue capability");
    }
    @Test void aWholeSeasonAbsoluteRangeAnchorsSplitCourNumbersAndDoesNotDriftAfterScopeFiltering() {
        Target target=new Target(); target.setSubjectId("100"); target.setTitle("Example 第四季 夺还篇");
        for(int n=78;n<=85;n++) target.getChapterTypes().put("NORMAL:"+n,"NORMAL");
        var fact=new ReleaseAnalysisService().parse("[A] Example 第四季合集 全 [总第67~85][1080p][CHS]","100","RSS");
        SubjectEpisodeResolver.learnSeasonStart(target,List.of(fact)); assertEquals(67,target.getSeasonStartChapter());
        assertEquals("78",SubjectEpisodeResolver.canonicalChapter(target,4,"12")); assertEquals("12",SubjectEpisodeResolver.displayChapter(target,4,"78"));
        assertEquals("1",SubjectEpisodeResolver.canonicalChapter(target,4,"1"));
        SubjectEpisodeResolver.normalizeChapters(target,fact); assertEquals(8,fact.getEpisodes().size());
        SubjectEpisodeResolver.learnSeasonStart(target,List.of(fact)); assertEquals(67,target.getSeasonStartChapter());
        var ani=new Ani().setId("s").setTitle("Example 第二季").setSeason(2).setQualityPolicyId("p");
        var wrong=new Item().setTitle("[A] Example S01E01 [1080p][CHS]").setEpisode(1D).setInfoHash("wrong-season");
        assertTrue(SubscriptionQualitySelector.select(ani,List.of(wrong),QualityPolicyPresets.catalog().presets().get(0).policy()).isEmpty());
        ani.setTitle("Example 第一季"); assertFalse(SubscriptionQualitySelector.select(ani,List.of(wrong),QualityPolicyPresets.catalog().presets().get(0).policy()).isEmpty());
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void expiredRssWindowRetainsHistoricalAlternativesAndCoverageDoesNotHideTheMissingChapter() throws Exception {
        String prior=System.getProperty("CONFIG"); var config=ani.rss.util.other.ConfigUtil.snapshot();
        System.setProperty("CONFIG",directory.resolve("config").toString()); ani.rss.persistence.DatabaseManager.close();
        ani.rss.util.other.ConfigUtil.CONFIG.setUpgrade(new UpgradeSettings());
        try {
            var repo=new UpgradeRepository(); var target=new Target(); target.setId("t"); target.setSubjectId("100"); target.setTitle("Blue Box"); target.setLibraryId("UNASSIGNED");
            target.getChapterTypes().put("NORMAL:1","NORMAL"); target.getChapterTypes().put("NORMAL:2","NORMAL");
            repo.save("upgrade_targets","t","UNASSIGNED","100:UNASSIGNED","MONITORING",1,target);
            Release release=new ReleaseAnalysisService().parse("[LoliHouse] Blue Box - 01 [1080p][CHS]","100","RSS");
            release.setTargetId("t"); release.setInfoHash("a".repeat(40)); release.setTorrentUrl("magnet:?xt=urn:btih:"+release.getInfoHash()); release.setSourceId("old");
            new ReleaseProvider(repo,new ReleaseAnalysisService()).accept(release);
            Ani subscription=new Ani().setBgmUrl("https://bgm.tv/subject/100").setSeason(1).setUrl("https://rss.test/feed").setQualityPolicyId("p").setExclude(List.of()).setMatch(List.of()).setGlobalExclude(false);
            var history=SubscriptionReleaseHistory.merge(subscription,subscription.getUrl(),List.of()); assertEquals(1,history.size()); assertEquals(release.getInfoHash(),history.get(0).getInfoHash());
            subscription.setExclude(List.of("Blue Box")); assertTrue(SubscriptionReleaseHistory.merge(subscription,subscription.getUrl(),List.of()).isEmpty());
            var chapters=new Cursor(); chapters.setId("chapters:t"); chapters.setComplete(true); repo.save("upgrade_scan_cursors",chapters.getId(),"t",null,"COMPLETE",1,chapters);
            var garden=new Cursor(); garden.setId("garden:t"); garden.setSearchRevision(1); garden.setComplete(true); garden.setLastFull(System.currentTimeMillis()); repo.save("upgrade_scan_cursors",garden.getId(),"t",null,"COMPLETE",1,garden);
            var coverage=UpgradeCoverageService.assess(repo,target,QualityPolicyPresets.catalog().presets().get(0).policy());
            assertEquals("COVERAGE_GAPS",coverage.get("state")); assertEquals(Set.of("NORMAL:2"),coverage.get("missingChapters"));
            garden.setError("SOURCE_OFFLINE"); repo.save("upgrade_scan_cursors",garden.getId(),"t",null,"DEFERRED",1,garden);
            assertEquals("SOURCE_INCOMPLETE",UpgradeCoverageService.assess(repo,target,QualityPolicyPresets.catalog().presets().get(0).policy()).get("state"));
            target.getAliases().add("Blue Box 第二季");
            repo.save("upgrade_targets","t","UNASSIGNED","100:UNASSIGNED","MONITORING",1,target);
            release.setSeason(2);
            repo.save("upgrade_releases",release.getId(),"t",release.getInfoHash(),release.getState(),1,release);
            subscription.setSeason(7).setExclude(List.of());
            var remappedHistory=SubscriptionReleaseHistory.merge(subscription,subscription.getUrl(),List.of());
            assertEquals(1,remappedHistory.size(),"A library folder season must not discard a verified story-season release");
            assertEquals(2,remappedHistory.get(0).getExpectedSourceSeason());
        } finally { ani.rss.persistence.DatabaseManager.close(); cn.hutool.core.bean.BeanUtil.copyProperties(config,ani.rss.util.other.ConfigUtil.CONFIG); if(prior==null)System.clearProperty("CONFIG");else System.setProperty("CONFIG",prior); }
    }
    @Test void registeredSeasonAliasMapsBothNumberingSystemsWithoutGuessingOtherSeasons() {
        Target target=new Target(); target.setSubjectId("100"); target.setTitle("一念永恒 完结季"); target.getAliases().add("一念永恒 第4季");
        for(int n=166;n<=212;n++) target.getChapterTypes().put("NORMAL:"+n,"NORMAL");
        assertEquals(4,SubjectEpisodeResolver.inferredSeason(target));
        assertEquals("166",SubjectEpisodeResolver.canonicalChapter(target,4,"1"));
        assertEquals("166",SubjectEpisodeResolver.canonicalChapter(target,4,"166"));
        assertEquals("1",SubjectEpisodeResolver.displayChapter(target,4,"166"));
        assertEquals("1",SubjectEpisodeResolver.canonicalChapter(target,3,"1"));
        var wrong=new ReleaseAnalysisService().parse("[GM-Team] 一念永恒 第3季 - 01 [1080p][CHS]","100","TEST");
        SubjectEpisodeResolver.normalizeChapters(target,wrong);
        assertEquals(QualityPolicyEngine.Verdict.IDENTITY_CONFLICT,new QualityPolicyEngine().evaluate(QualityPolicyPresets.catalog().presets().get(0).policy(),wrong,null).verdict());
        assertTrue(SourceIdentityRules.titleMatches(target,"[GM-Team] 一念永恒 第4季 [01-04][GB][1080p]"));
        assertFalse(SourceIdentityRules.titleMatches(target,"[GM-Team] 一念永恒 第3季 [01][GB][1080p]"));
        target.getEpisodeMapping().put("4:1","180"); assertEquals("180",SubjectEpisodeResolver.canonicalChapter(target,4,"1"));
        Target continuous=new Target(); continuous.setTitle("Example 第三季"); for(int n=25;n<=36;n++) continuous.getChapterTypes().put("NORMAL:"+n,"NORMAL");
        var platformLabel=new ReleaseAnalysisService().parse("[A] Example S01E25 [1080p][CHS]","100","TEST");
        SubjectEpisodeResolver.normalizeChapters(continuous,platformLabel); assertEquals(3,platformLabel.getSeason());
        assertTrue(platformLabel.getEvidence().stream().noneMatch(e->"SOURCE_SEASON_CONFLICT".equals(e.getSource())));
        wrong.getEvidence().add(new Evidence("USER_CORRECTION","fields","Explicit operator correction")); wrong.setEpisodes(Set.of("200"));
        SubjectEpisodeResolver.normalizeChapters(target,wrong); assertEquals(Set.of("200"),wrong.getEpisodes());
        assertTrue(wrong.getEvidence().stream().noneMatch(e->Set.of("SOURCE_SEASON_CONFLICT","CHAPTER_IDENTITY_UNRESOLVED").contains(e.getSource())));
    }
    @Test void shortNamesNeedStandaloneIdentityAndAnExplicitBindingCanRepairOldRejection() {
        Target target=new Target(); target.setTitle("GREATEST");
        assertTrue(SourceIdentityRules.titleMatches(target,"[Artist] GREATEST [MV][1080p][CHS]"));
        assertFalse(SourceIdentityRules.titleMatches(target,"[Group] Possibly the Greatest Alchemist of All Time [1080p][CHS]"));
        var release=new Release(); release.getEvidence().add(new Evidence("PROVIDER_SUBJECT_BINDING","identity","100"));
        assertTrue(SourceIdentityRules.bound(release));
    }
    @Test void mixedSingleAndCollectionSelectsOnlyTheBestFileForEachChapter() {
        Ani ani=new Ani().setId("s").setSeason(1).setOffset(0).setOva(false).setBgmUrl("https://bgm.tv/subject/100").setQualityPolicyId("p");
        Item batch=new Item().setTitle("[LoliHouse] Example [01-03][1080p][CHS]").setEpisode(1D).setInfoHash("batch");
        Item single=new Item().setTitle("[桜都] Example - 02 [1080p][CHS]").setEpisode(2D).setInfoHash("single");
        var selected=SubscriptionQualitySelector.select(ani,List.of(batch,single),QualityPolicyPresets.catalog().presets().get(0).policy());
        assertEquals(2,selected.size()); assertEquals(Set.of("1","3"),batch.getSelectedEpisodes()); assertEquals(Set.of("2"),single.getSelectedEpisodes());
        Set<String> chapters=SubscriptionQualitySelector.selectedChapters(List.of(SubscriptionQualitySelector.selectionTag(batch)));
        assertEquals(SubscriptionQualitySelector.FileSelection.SELECT,SubscriptionQualitySelector.selectedFile(ani,"Example - 01.mkv",chapters));
        assertEquals(SubscriptionQualitySelector.FileSelection.SKIP,SubscriptionQualitySelector.selectedFile(ani,"Example - 02.chs.ass",chapters));
        assertEquals(SubscriptionQualitySelector.FileSelection.UNKNOWN,SubscriptionQualitySelector.selectedFile(ani,"fonts/font.ttf",chapters));
        assertEquals(SubscriptionQualitySelector.FileSelection.SKIP,SubscriptionQualitySelector.selectedFile(ani,"NCOP.mkv",chapters));
        assertThrows(IllegalArgumentException.class,()->SubscriptionQualitySelector.selectedFile(ani,"Example [01-03].mkv",chapters));
        ani.setOva(true); batch.setEpisode(1D); single.setEpisode(1D);
        SubscriptionQualitySelector.select(ani,List.of(batch,single),QualityPolicyPresets.catalog().presets().get(0).policy());
        assertEquals(Set.of("1","3"),batch.getSelectedEpisodes()); assertEquals(Set.of("2"),single.getSelectedEpisodes()); assertEquals(2D,single.getEpisode());
    }
    @Test void anOwnedEqualVersionAvoidsPartialDuplicatesButAnActualQualityUpgradeRemainsEligible() {
        Ani ani=new Ani().setId("s").setSeason(1).setOffset(0).setBgmUrl("https://bgm.tv/subject/100");
        Item batch=new Item().setTitle("[LoliHouse] Example [01-03][1080p][CHS]").setEpisode(1D).setInfoHash("b");
        Item previous=new Item().setTitle("[LoliHouse] Example - 01 [1080p][CHS]").setEpisode(1D).setInfoHash("a");
        var task=new TorrentsInfo().setHash("a").setName("Example S01E01").setState(TorrentsStateEnum.queuedDL);
        var policy=QualityPolicyPresets.catalog().presets().get(0).policy();
        assertEquals(Set.of("2","3"),SubscriptionQualitySelector.missingBatchChapters(ani,batch,List.of(task),t->true,Map.of("a",previous),policy));
        assertEquals(Set.of("1","2","3"),SubscriptionQualitySelector.missingBatchChapters(ani,batch,List.of(task),t->false,Map.of("a",previous),policy));
        batch.setTitle("[桜都] Example [01-03][1080p][CHS]");
        assertEquals(Set.of("1","2","3"),SubscriptionQualitySelector.missingBatchChapters(ani,batch,List.of(task),t->true,Map.of("a",previous),policy));
    }
}
