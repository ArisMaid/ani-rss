package ani.rss.service;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GroupRegexUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.comparator.WeekComparator;
import ani.rss.entity.Ani;
import ani.rss.entity.AnimeGarden;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.GroupRegex;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnimeGardenService {
    private static final String HOST = "https://api.animes.garden";
    private static final int ANIME_GARDEN_REQUEST_TIMEOUT_MILLIS = 10_000;

    @Resource
    private CacheService cacheService;

    @Resource
    private PublicScoreService publicScoreService;

    public List<AnimeGarden.Week> list(String bgmUrl) {
        List<AnimeGarden.Week> weekList = new ArrayList<>();

        if (StrUtil.isNotBlank(bgmUrl)) {
            AnimeGarden.Week week = new AnimeGarden.Week();
            weekList.add(week);

            String bgmId = BgmUtil.getSubjectId(bgmUrl);
            BgmInfo bgmInfo = BgmUtil.getBgmInfo(bgmId);
            String name = BgmUtil.getFinalName(bgmInfo);
            String cover = Optional.ofNullable(bgmInfo.getImages())
                    .map(BgmInfo.Images::getSmall)
                    .orElse("");
            double score = Optional.ofNullable(bgmInfo.getRating())
                    .map(BgmInfo.Rating::getScore)
                    .orElse(0.0);

            AnimeGarden.Subject subject = new AnimeGarden.Subject();
            subject.setName(name)
                    .setId(bgmId)
                    .setCover(cover)
                    .setScore(score)
                    .setExists(true);

            week.setWeekLabel("搜索")
                    .setSubjects(List.of(subject));
            return weekList;
        }

        JsonObject bgmCover = cacheService.getBgmCover();

        List<String> bgmIdList = AniUtil.ANI_LIST
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .distinct()
                .toList();

        List<AnimeGarden.Subject> subjectList;
        try {
            subjectList = HttpReq.get(HOST + "/subjects")
                    .timeout(ANIME_GARDEN_REQUEST_TIMEOUT_MILLIS)
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonArray subjects = jsonObject.getAsJsonArray("subjects");
                        return GsonStatic.fromJsonList(subjects, AnimeGarden.Subject.class);
                    });
        } catch (Exception e) {
            log.warn("AnimeGarden subject request failed");
            return weekList;
        }

        Map<String, Double> resolvedBgmScores;
        try {
            resolvedBgmScores = publicScoreService.getBgmScores(
                    subjectList.stream()
                            .map(AnimeGarden.Subject::getId)
                            .filter(StrUtil::isNotBlank)
                            .toList()
            );
        } catch (RuntimeException e) {
            // Scores are optional; AnimeGarden itself must remain usable if an upstream score source is down.
            resolvedBgmScores = Map.of();
        }
        final Map<String, Double> bgmScores = resolvedBgmScores;

        subjectList = subjectList.stream()
                .peek(subject -> {
                    String id = subject.getId();

                    Double score = bgmScores.getOrDefault(id, 0.0);

                    String cover = Optional.ofNullable(bgmCover.get(id))
                            .map(it -> GsonStatic.fromJson(it, BgmInfo.Images.class))
                            .map(BgmInfo.Images::getSmall)
                            .orElse("");

                    boolean exists = bgmIdList.contains(subject.getId());

                    subject
                            .setScore(score)
                            .setCover(cover)
                            .setExists(exists);
                })
                .sorted(Comparator.comparingDouble(AnimeGarden.Subject::getScore).reversed())
                .toList();

        List<String> weeks = List.of("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六");

        Map<String, List<AnimeGarden.Subject>> map = subjectList.stream()
                .peek(subject -> {
                    Date activedAt = subject.getActivedAt();
                    int i = DateUtil.dayOfWeek(activedAt) - 1;
                    String weekLabel = weeks.get(i);
                    subject.setWeekLabel(weekLabel);
                })
                .collect(Collectors.groupingBy(AnimeGarden.Subject::getWeekLabel));

        for (String weekLabel : weeks) {
            if (!map.containsKey(weekLabel)) {
                continue;
            }

            AnimeGarden.Week week = new AnimeGarden.Week();
            week.setWeekLabel(weekLabel)
                    .setSubjects(map.get(weekLabel));
            weekList.add(week);
        }

        WeekComparator weekComparator = new WeekComparator();
        weekList = weekList.stream()
                .sorted((a, b) ->
                        weekComparator.compare(a.getWeekLabel(), b.getWeekLabel())
                ).toList();

        return weekList;
    }

    public List<AnimeGarden.Group> group(String bgmId) {
        List<AnimeGarden.Item> items;
        try {
            items = HttpReq.get(HOST + "/resources")
                    .timeout(ANIME_GARDEN_REQUEST_TIMEOUT_MILLIS)
                    .form("subject", bgmId)
                    .form("pageSize", 200)
                    .form("duplicate", false)
                    .thenFunction(res -> {
                        HttpReq.assertStatus(res);
                        JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                        JsonArray resources = jsonObject.getAsJsonArray("resources");
                        return GsonStatic.fromJsonList(resources, AnimeGarden.Item.class);
                    });
        } catch (Exception e) {
            log.warn("AnimeGarden group request failed");
            return List.of();
        }

        items = items
                .stream()
                .filter(it -> {
                    AnimeGarden.Fansub fansub = it.getFansub();
                    return Objects.nonNull(fansub);
                })
                .peek(it -> {
                    Long size = it.getSize();
                    String formatSize = FileUtils.formatSize(size, true);
                    it.setFormatSize(formatSize);
                })
                .toList();


        Map<String, List<AnimeGarden.Item>> groupIdMap = items.stream()
                .collect(Collectors.groupingBy(it -> it.getFansub().getId()));

        List<AnimeGarden.Group> list = items
                .stream()
                .map(it -> {
                    AnimeGarden.Fansub fansub = it.getFansub();
                    String id = fansub.getId();
                    String name = fansub.getName();
                    Date createdAt = it.getCreatedAt();

                    String rss = StrUtil.format(
                            "{}/feed.xml?subject={}&fansub={}",
                            HOST,
                            bgmId,
                            name.replace("&", "%26")
                    );

                    return new AnimeGarden.Group()
                            .setId(id)
                            .setName(name)
                            .setLastUpdatedAt(createdAt)
                            .setRss(rss)
                            .setBgmId(bgmId);
                })
                .sorted(Comparator.comparing(AnimeGarden.Group::getLastUpdatedAt).reversed())
                .toList();

        list = CollUtil.distinct(list, AnimeGarden.Group::getId, false);

        for (AnimeGarden.Group group : list) {
            String id = group.getId();
            List<AnimeGarden.Item> itemList = groupIdMap.get(id);
            GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(itemList, AnimeGarden.Item::getTitle);

            group.setItems(itemList)
                    .setGroupRegex(groupRegx);
        }

        return list;
    }
}
