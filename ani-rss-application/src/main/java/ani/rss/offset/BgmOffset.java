package ani.rss.offset;

import ani.rss.entity.Ani;
import ani.rss.entity.BgmEpisodes.BgmEpisode;
import ani.rss.util.other.BgmUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class BgmOffset implements BaseOffset {
    // 主备 RSS 共享同一作品的正篇起始集数，订阅字段变化不影响缓存键。
    private final Map<String, Integer> minEpisodesBySubject = new HashMap<>();

    @Override
    public int getMinEpisode(Ani ani) {
        return minEpisodesBySubject.computeIfAbsent(BgmUtil.getSubjectId(ani), subjectId ->
                BgmUtil.getEpisodes(subjectId, 0).stream()
                        .map(BgmEpisode::getSort)
                        .filter(Objects::nonNull)
                        .mapToInt(Integer::intValue)
                        .min()
                        .orElse(1));
    }
}
