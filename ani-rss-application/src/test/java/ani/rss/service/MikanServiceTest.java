package ani.rss.service;

import ani.rss.entity.Mikan;
import ani.rss.entity.MikanBgm;
import ani.rss.entity.MikanInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MikanServiceTest {
    @Test
    void appliesScoresToEverySeasonResponseAndResetsMissingScoresToZero() {
        Mikan spring = season("101", "102");
        MikanService.applyScores(
                spring,
                Map.of(
                        "101", new MikanBgm("101", "201", 7.4),
                        "102", new MikanBgm("102", "202", 8.5)
                ),
                Set.of("202")
        );

        List<MikanInfo> springItems = spring.getWeeks().get(0).getItems();
        assertEquals("102", PublicScoreService.extractMikanId(springItems.get(0).getUrl()));
        assertEquals(8.5, springItems.get(0).getScore());
        assertTrue(springItems.get(0).getExists());
        assertEquals(7.4, springItems.get(1).getScore());

        Mikan summer = season("301", "302");
        MikanService.applyScores(
                summer,
                Map.of("301", new MikanBgm("301", "401", 9.1)),
                Set.of()
        );

        List<MikanInfo> summerItems = summer.getWeeks().get(0).getItems();
        assertEquals(9.1, summerItems.get(0).getScore());
        assertEquals(0.0, summerItems.get(1).getScore());
    }

    private static Mikan season(String first, String second) {
        List<MikanInfo> items = new ArrayList<>(List.of(
                new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + first),
                new MikanInfo().setUrl("https://mikanani.me/Home/Bangumi/" + second)
        ));
        return new Mikan().setWeeks(List.of(new Mikan.Week().setItems(items)));
    }
}
