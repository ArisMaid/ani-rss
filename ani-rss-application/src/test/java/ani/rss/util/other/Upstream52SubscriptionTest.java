package ani.rss.util.other;

import ani.rss.commons.PinyinUtils;
import ani.rss.entity.*;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.enums.OffsetTypeEnum;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Upstream52SubscriptionTest {
    @Test void filteredGardenFeedCanRetainTheExplicitBangumiIdentityWithoutASubjectQueryParameter() {
        Config original=ConfigUtil.snapshot(); ConfigUtil.CONFIG.setOffset(false).setCopyMasterToStandby(false);
        try(var bgm=mockStatic(BgmUtil.class);var items=mockStatic(ItemsUtil.class)) {
            bgm.when(()->BgmUtil.toAni(any(),any())).thenAnswer(inv->{((Ani)inv.getArgument(1)).setOva(false).setTitle("Example");return null;});
            var dto=new RssToAniDTO().setType("anime-garden").setUrl("https://api.animes.garden/feed.xml?include=Example").setBgmUrl("https://bgm.tv/subject/123").setSubgroup("preferred");
            Ani result=AniUtil.getAni(dto); assertEquals("https://bgm.tv/subject/123",result.getBgmUrl()); assertEquals("preferred",result.getSubgroup());
        } finally {BeanUtil.copyProperties(original,ConfigUtil.CONFIG);}
    }
    @ParameterizedTest
    @EnumSource(OffsetTypeEnum.class)
    void eachStandbyFeedInfersItsOwnOffsetAgainstUnshiftedEpisodes(OffsetTypeEnum offsetType) {
        Config original = ConfigUtil.snapshot();
        ConfigUtil.CONFIG.setOffset(true).setOffsetType(offsetType)
                .setCopyMasterToStandby(false).setStandbyRss(true);
        try (var bgm = mockStatic(BgmUtil.class); var items = mockStatic(ItemsUtil.class, CALLS_REAL_METHODS)) {
            bgm.when(() -> BgmUtil.toAni(any(), any())).thenAnswer(invocation -> {
                invocation.<Ani>getArgument(1).setOva(false).setTitle("Example").setSubgroup("Group").setSeason(2);
                return null;
            });
            bgm.when(() -> BgmUtil.getSubjectId(any(Ani.class))).thenReturn("123");
            bgm.when(() -> BgmUtil.getEpisodes("123", 0)).thenReturn(List.of(
                    new BgmEpisodes.BgmEpisode().setSort(15),
                    new BgmEpisodes.BgmEpisode(),
                    new BgmEpisodes.BgmEpisode().setSort(13)));
            items.when(() -> ItemsUtil.getItems(any(Ani.class), anyString(), anyString())).thenAnswer(invocation -> {
                Ani unshifted = invocation.getArgument(0);
                assertEquals(0, unshifted.getOffset());
                String url = invocation.getArgument(1);
                double episode = url.endsWith("primary") ? 13 : url.endsWith("standby") ? 25 : 12.5;
                return List.of(new Item().setEpisode(episode), new Item().setEpisode(null));
            });
            var dto = new RssToAniDTO().setType("other").setUrl("https://example.test/primary")
                    .setBgmUrl("https://bgm.tv/subject/123").setStandbyRssList(List.of(
                            new StandbyRss().setLabel("B").setUrl("https://example.test/standby"),
                            new StandbyRss().setLabel("C").setUrl("https://example.test/half")));

            Ani result = AniUtil.getAni(dto);
            boolean useBgm = offsetType == OffsetTypeEnum.BGM_OFFSET;
            assertEquals(useBgm ? 0 : -12, result.getOffset());
            assertEquals(useBgm ? -12 : -24, result.getStandbyRssList().get(0).getOffset());
            assertEquals(useBgm ? 0 : -12, result.getStandbyRssList().get(1).getOffset());
            assertEquals(useBgm ? 1 : 2, result.getSeason());
            assertNull(dto.getStandbyRssList().get(0).getOffset());
            bgm.verify(() -> BgmUtil.getEpisodes("123", 0), times(useBgm ? 1 : 0));
        } finally {
            BeanUtil.copyProperties(original, ConfigUtil.CONFIG);
        }
    }
    @Test
    void renameKeepsTheNcoPedMarkerInTheReleaseTitle() {
        Ani ani = AniUtil.createAni().setTitle("Example").setOva(false).setSeason(1).setOffset(0)
                .setBgmUrl("https://bgm.tv/subject/123")
                .setCustomEpisode(true).setCustomEpisodeStr("第(\\d+)集").setCustomEpisodeGroupIndex(1)
                .setCustomRenameTemplateEnable(true).setCustomRenameTemplate("${itemTitle}");
        Item item = new Item().setTitle("Example 第13集+NCOPED");

        assertTrue(RenameUtil.rename(ani, item));
        assertEquals(13D, item.getEpisode());
        assertTrue(item.getReName().contains("+NCOPED"));
    }

    @Test void completedSubscriptionsDoNotEmitStaleReleaseWarnings() {
        Config original=ConfigUtil.snapshot();ConfigUtil.CONFIG.setProcrastinating(true).setProcrastinatingDay(1).setProcrastinatingMasterOnly(true);
        Ani ani=new Ani().setId("completed-warning").setTitle("Completed").setTotalEpisodeNumber(2).setDownloadNew(false).setProcrastinating(true);
        try(var notifications=mockStatic(NotificationUtil.class)) {
            ItemsUtil.procrastinating(ani,List.of(new Item().setEpisode(1D).setMaster(true).setPubDate(new Date(0)),new Item().setEpisode(2D).setMaster(true).setPubDate(new Date(0))));
            notifications.verifyNoInteractions();
        } finally {BeanUtil.copyProperties(original,ConfigUtil.CONFIG);}
    }
    @Test void polyphonicTitlesKeepTheirIntendedPronunciation() {
        assertEquals("chong sheng",PinyinUtils.getPinyin("重生").toLowerCase(Locale.ROOT));
        assertEquals("chong qing",PinyinUtils.getPinyin("重庆").toLowerCase(Locale.ROOT));
        assertEquals("tiao jiao",PinyinUtils.getPinyin("调教").toLowerCase(Locale.ROOT));
    }
}
