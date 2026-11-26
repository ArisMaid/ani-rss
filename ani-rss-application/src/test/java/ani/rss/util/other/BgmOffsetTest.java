package ani.rss.util.other;

import ani.rss.commons.CacheUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.BgmEpisodes.BgmEpisode;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.enums.OffsetTypeEnum;
import ani.rss.offset.BgmOffset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;

class BgmOffsetTest {
    @Test
    void keepsEpisodeLookupAndTitlesInTheSelectedNumbering() {
        String subjectId = "902345";
        Ani ani = new Ani().setBgmUrl("https://bgm.tv/subject/" + subjectId).setOva(false);
        Config config = new Config().setOffsetType(null);
        try (var bgm = mockStatic(BgmUtil.class); var settings = mockStatic(ConfigUtil.class)) {
            settings.when(ConfigUtil::snapshot).thenReturn(config);
            bgm.when(() -> BgmUtil.getSubjectId(ani)).thenReturn(subjectId);
            bgm.when(() -> BgmUtil.getEpisodes(subjectId, 0)).thenReturn(List.of(
                    new BgmEpisode().setId("24").setEp(1).setSort(13).setName("Opening"),
                    new BgmEpisode().setId("25").setSort(14).setName("Second"),
                    new BgmEpisode().setId("26").setEp(3).setName("Third"),
                    new BgmEpisode().setId("27")));
            bgm.when(() -> BgmUtil.getEpisodeId(subjectId, null)).thenCallRealMethod();
            bgm.when(() -> BgmUtil.getEpisodeId(subjectId, 1D)).thenCallRealMethod();
            bgm.when(() -> BgmUtil.getEpisodeId(subjectId, 13D)).thenCallRealMethod();
            bgm.when(() -> BgmUtil.getEpisodeTitleMap(ani)).thenCallRealMethod();

            assertEquals("", BgmUtil.getEpisodeId(subjectId, null));
            assertEquals("24", BgmUtil.getEpisodeId(subjectId, 1D));
            assertEquals("", BgmUtil.getEpisodeId(subjectId, 13D));
            assertEquals(Set.of(1, 3), BgmUtil.getEpisodeTitleMap(ani).keySet());

            config.setOffsetType(OffsetTypeEnum.BGM_OFFSET);
            assertEquals("24", BgmUtil.getEpisodeId(subjectId, 13D));
            assertEquals("", BgmUtil.getEpisodeId(subjectId, 1D));
            assertEquals(Set.of(13, 14), BgmUtil.getEpisodeTitleMap(ani).keySet());
            assertEquals("Opening", BgmUtil.getEpisodeTitleMap(ani).get(13).getName());

            config.setOffsetType(OffsetTypeEnum.AUTO_OFFSET);
            assertEquals(Set.of(1, 3), BgmUtil.getEpisodeTitleMap(ani).keySet());
            bgm.verify(() -> BgmUtil.getEpisodes(subjectId, 0), times(3));
        } finally {
            CacheUtils.remove("BGM_getEpisodeId:" + subjectId);
            for (OffsetTypeEnum offsetType : OffsetTypeEnum.values()) {
                CacheUtils.remove("BGM_getEpisodeTitleMap:" + subjectId + ":" + offsetType);
            }
        }
    }

    @Test
    void defaultsToEpisodeOneWhenBangumiHasNoUsableNumber() {
        Ani ani = new Ani().setSeason(2);
        try (var bgm = mockStatic(BgmUtil.class)) {
            bgm.when(() -> BgmUtil.getSubjectId(ani)).thenReturn("902346");
            bgm.when(() -> BgmUtil.getEpisodes("902346", 0)).thenReturn(List.of(new BgmEpisode()));
            BgmOffset strategy = new BgmOffset();

            assertEquals(0, strategy.getOffset(ani, List.of(new Item())));
            bgm.verifyNoInteractions();
            assertEquals(-4, strategy.getOffset(ani, List.of(new Item().setEpisode(5D))));
            assertEquals(2, ani.getSeason());
        }
    }
}
