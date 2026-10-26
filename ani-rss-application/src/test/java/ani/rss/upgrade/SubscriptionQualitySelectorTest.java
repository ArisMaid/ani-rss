package ani.rss.upgrade;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.ItemsUtil;
import org.junit.jupiter.api.Test;
import java.util.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SubscriptionQualitySelectorTest {
    @Test void aBatchCannotRepeatEpisodesAlreadySubmittedAsOwnedIndividualTasks() {
        var subscription=new Ani().setId("s").setSeason(1).setOffset(0).setQualityPolicyId("p");
        Item batch=item("桜都",1080,"CHS",1,"batch").setTitle("[桜都] Example S01E01-03 [1080p][CHS]");
        var tasks=new ArrayList<ani.rss.entity.torrent.TorrentsInfo>();
        for(int episode=1;episode<=3;episode++) tasks.add(new ani.rss.entity.torrent.TorrentsInfo().setHash("single-"+episode).setName("Example S01E0"+episode));
        assertTrue(SubscriptionQualitySelector.alreadyCoveredBatch(subscription,batch,tasks,task->true));
        assertFalse(SubscriptionQualitySelector.alreadyCoveredBatch(subscription,batch,tasks.subList(0,2),task->true));
        assertFalse(SubscriptionQualitySelector.alreadyCoveredBatch(subscription,batch,tasks,task->false));
        assertFalse(SubscriptionQualitySelector.alreadyCoveredBatch(subscription,batch,List.of(new ani.rss.entity.torrent.TorrentsInfo().setHash("batch").setName(batch.getTitle())),task->true));
        tasks.get(2).setName("Example S02E03"); assertFalse(SubscriptionQualitySelector.alreadyCoveredBatch(subscription,batch,tasks,task->true));
    }
    @Test void collectionsKeepEveryCoveredEpisodeAndDistinctVideoFilenames() {
        var ani=new Ani().setId("s").setSeason(1).setOffset(0).setQualityPolicyId("p");
        var batch=item("桜都",1080,"CHS",1,"a").setTitle("[桜都] Example [01-03 合集][1080p][CHS]");
        var result=SubscriptionQualitySelector.select(ani,List.of(item("桜都",1080,"CHS",2,"b"),batch,item("Other",1080,"CHS",4,"c")),QualityPolicyPresets.catalog().presets().get(0).policy());
        assertEquals(List.of("a","c"),result.stream().map(Item::getInfoHash).toList());
        assertEquals(Set.of("1","2","3"),SubscriptionQualitySelector.coveredEpisodes(ani,batch));
        assertEquals(4,ItemsUtil.currentEpisodeNumber(ani,result));
        assertTrue(SubscriptionQualitySelector.preserveBatchFilenames(ani,"Example S01E01-03",List.of("01.mkv","02.mkv")));
        assertTrue(SubscriptionQualitySelector.preserveBatchFilenames(ani,"Example",List.of("Example - 01.mkv","Example - 02.mkv")));
        assertFalse(SubscriptionQualitySelector.preserveBatchFilenames(ani,"Example S01E01",List.of("Example - 01 [1080p].mkv","Example - 01 [2160p].mkv")));
    }
    @TempDir Path directory;
    @Test void actualRssParserRanksVersionsBeforeItRemovesDuplicateEpisodes() {
        String prior=System.getProperty("CONFIG"); var original=ani.rss.util.other.ConfigUtil.snapshot();
        System.setProperty("CONFIG",directory.resolve("config").toString()); ani.rss.persistence.DatabaseManager.close();
        try {
            var policy=QualityPolicyPresets.catalog().presets().get(0).policy(); policy.setId("p"); new UpgradeRepository().save("upgrade_policies","p",null,null,"ACTIVE",1,policy);
            ani.rss.util.other.ConfigUtil.CONFIG.setSkip5(false);
            var subscription=ani.rss.util.other.AniUtil.createAni().setTitle("Example").setSeason(1).setOva(false).setCustomEpisode(false).setBgmUrl("https://bgm.tv/subject/100").setUrl("https://rss.example/feed").setQualityPolicyId("p").setExclude(List.of()).setGlobalExclude(false).setCustomRenameTemplateEnable(true).setCustomRenameTemplate("${subgroup} S${seasonFormat}E${episodeFormat}");
            String xml="<rss><channel><item><title>[LoliHouse] Example - 01 [1080p][CHS]</title><guid>"+"a".repeat(40)+"</guid><enclosure url='https://rss.example/a.torrent' length='100'/></item><item><title>[桜都] Example - 01 [1080p][CHS]</title><guid>"+"b".repeat(40)+"</guid><enclosure url='magnet:?xt=urn:btih:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' length='200'/></item></channel></rss>";
            try(var rss=org.mockito.Mockito.mockStatic(ani.rss.util.other.ItemsUtil.class)) {
                rss.when(()->ani.rss.util.other.ItemsUtil.getRss(subscription.getUrl())).thenReturn(xml);
                rss.when(()->ani.rss.util.other.ItemsUtil.getItems(subscription,subscription.getUrl(),"自动优选")).thenCallRealMethod();
                rss.when(()->ani.rss.util.other.ItemsUtil.isAllowedByDownloadRules(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenCallRealMethod();
                var result=ani.rss.util.other.ItemsUtil.getItems(subscription,subscription.getUrl(),"自动优选");
                assertEquals(1,result.size()); assertEquals("0".repeat(40),result.get(0).getInfoHash()); assertEquals(200L,result.get(0).getLength()); assertTrue(result.get(0).getReName().startsWith("桜都"));
            }
        } finally { ani.rss.persistence.DatabaseManager.close(); cn.hutool.core.bean.BeanUtil.copyProperties(original,ani.rss.util.other.ConfigUtil.CONFIG); if(prior==null) System.clearProperty("CONFIG"); else System.setProperty("CONFIG",prior); }
    }
    private Item item(String group,int resolution,String language,double episode,String hash) {
        return new Item().setTitle("["+group+"] Example - "+(int)episode+" ["+resolution+"p]["+language+"]").setEpisode(episode).setInfoHash(hash).setSubgroup("自动优选");
    }
    @Test void selectsAllQualifyingEpisodesWithPerEpisodeFallbackAndOnePreferredVersion() {
        var policy=QualityPolicyPresets.catalog().presets().get(0).policy(); var ani=new Ani().setId("subscription").setSeason(1).setBgmUrl("https://bgm.tv/subject/100");
        List<Item> items=List.of(item("LoliHouse",1080,"CHS",1,"a"),item("桜都",2160,"CHS",1,"b"),item("绿茶",1080,"CHS",1,"c"),item("桜都",720,"CHS",2,"d"),item("绿茶",1080,"CHT",2,"e"),item("Other",1080,"CHS",2,"f"),item("北宇治",1080,"CHS",3,"g"));
        List<Item> selected=SubscriptionQualitySelector.select(ani,items,policy);
        assertEquals(List.of("c","f","g"),selected.stream().map(Item::getInfoHash).toList());
        assertEquals(List.of("绿茶","Other","北宇治"),selected.stream().map(Item::getSubgroup).toList());
        assertEquals(6,policy.getGroups().size()); assertFalse(policy.getContainers().isEmpty());
    }
    @Test void unknownChineseScriptCannotPassRequiredSimplifiedAndUnconfiguredSubscriptionsKeepTheirOldPath() {
        var ani=new Ani().setId("s").setSeason(1); var items=List.of(item("桜都",1080,"JPN",1,"a"));
        assertTrue(SubscriptionQualitySelector.select(ani,items,QualityPolicyPresets.catalog().presets().get(0).policy()).isEmpty());
        assertSame(items,SubscriptionQualitySelector.select(ani,items));
    }
}
