package ani.rss.offset;

import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.util.other.ItemsUtil;

import java.util.List;
import java.util.Objects;

public interface BaseOffset {
    int getMinEpisode(Ani ani);

    default int getOffset(Ani ani, List<Item> items) {
        Double itemMinEpisode = items.stream()
                .map(Item::getEpisode)
                .filter(Objects::nonNull)
                .min(Double::compare)
                .orElse(null);
        if (itemMinEpisode == null) {
            return 0;
        }

        int minEpisode = getMinEpisode(ani);
        if (minEpisode > 1) {
            ani.setSeason(1);
        }
        // 半集对齐到目标首集之前的半集。
        return ItemsUtil.is5(itemMinEpisode)
                ? minEpisode - 1 - itemMinEpisode.intValue()
                : minEpisode - itemMinEpisode.intValue();
    }
}
