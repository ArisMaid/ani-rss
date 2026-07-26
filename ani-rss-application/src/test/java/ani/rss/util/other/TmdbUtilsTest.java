package ani.rss.util.other;

import ani.rss.entity.Config;
import cn.hutool.core.bean.BeanUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import wushuo.tmdb.api.entity.Tmdb;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TmdbUtilsTest {
    private final Config original = ConfigUtil.copy(ConfigUtil.CONFIG);

    @AfterEach
    void restoreConfiguration() {
        BeanUtil.copyProperties(original, ConfigUtil.CONFIG);
    }

    @Test
    void selectsOriginalTitleAndFallsBackWhenItIsUnavailable() {
        ConfigUtil.CONFIG.setTitleYear(false)
                .setTmdbId(false)
                .setTmdbOriginalName(true);
        Tmdb tmdb = new Tmdb()
                .setId("123")
                .setName("本地化标题")
                .setOriginalName("Original Title")
                .setDate(new Date());

        assertEquals("Original Title", TmdbUtils.getFinalName(tmdb));
        assertEquals("本地化标题", TmdbUtils.getFinalName(tmdb.setOriginalName("")));

        ConfigUtil.CONFIG.setTmdbOriginalName(false);
        assertEquals("本地化标题", TmdbUtils.getFinalName(
                tmdb.setOriginalName("Original Title")));
    }
}
