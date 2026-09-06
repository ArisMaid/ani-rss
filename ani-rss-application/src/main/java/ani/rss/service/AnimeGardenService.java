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
import java.util.concurrent.ConcurrentHashMap;
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

    /** IDs from the most recently rendered subjects list; enrichment cannot
     * turn this endpoint into an arbitrary Bangumi lookup proxy. */
    private final Set<String> loadedSubjectIds = ConcurrentHashMap.newKeySet();

    public List<AnimeGarden.Week> list(String bgmUrl) {
        List<AnimeGarden.Week> weekList = new ArrayList<>();
        loadedSubjectIds.clear();

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
            if (bgmId != null && bgmId.chars().allMatch(Character::isDigit)) {
                loadedSubjectIds.add(bgmId);
            }
            subject.setName(name)
                    .setId(bgmId)
                    .setCover(cover)
                    .setScore(score)
                    .setExists(true);

            week.setWeekLabel("搜索")
                    .setSubjects(List.of(subject));
            return weekList;
        }

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

        loadedSubjectIds.addAll(subjectList.stream()
                .map(AnimeGarden.Subject::getId)
                .filter(StrUtil::isNotBlank)
                .filter(id -> id.chars().allMatch(Character::isDigit))
                .toList());
        JsonObject bgmCover = cacheService.getBgmCoverSnapshot();
        Set<String> subscribedBgmIds = new HashSet<>(bgmIdList);

        // Subjects are the required list payload.  Cover and score are
        // optional enrichment and must not serialize the first response on
        // either public upstream.
        subjectList = subjectList.stream()
                .peek(subject -> {
                    String id = subject.getId();
                    String cover = Optional.ofNullable(bgmCover.get(id))
                            .map(it -> GsonStatic.fromJson(it, BgmInfo.Images.class))
                            .map(BgmInfo.Images::getSmall)
                            .orElse("");
                    Double score = Optional.ofNullable(subject.getScore()).orElse(0.0);
                    subject
                            .setScore(score)
                            .setCover(cover)
                            .setExists(subscribedBgmIds.contains(id));
                })
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

    public AnimeGarden.EnrichmentResponse enrich(Collection<String> subjectIds) {
        LinkedHashSet<String> ids = Optional.ofNullable(subjectIds)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> !id.isBlank() && id.chars().allMatch(Character::isDigit))
                .filter(loadedSubjectIds::contains)
                .distinct()
                .limit(48)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        JsonObject coverIndex = cacheService.getBgmCoverForEnrichment();
        PublicScoreService.BgmScoreLookup scoreLookup = publicScoreService.getCachedBgmScoresAndWarm(ids);
        Map<String, AnimeGarden.Enrichment> subjects = new LinkedHashMap<>();
        LinkedHashSet<String> retryable = new LinkedHashSet<>(scoreLookup.retryableSubjectIds());
        boolean coverIndexPending = coverIndex.isEmpty();
        for (String id : ids) {
            String cover = Optional.ofNullable(coverIndex.get(id))
                    .map(it -> GsonStatic.fromJson(it, BgmInfo.Images.class))
                    .map(BgmInfo.Images::getSmall)
                    .orElse("");
            if (coverIndexPending) {
                retryable.add(id);
            }
            subjects.put(id, new AnimeGarden.Enrichment()
                    .setCover(cover)
                    .setScore(scoreLookup.scores().get(id)));
        }
        return new AnimeGarden.EnrichmentResponse()
                .setSubjects(subjects)
                .setRetryableSubjectIds(new ArrayList<>(retryable));
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
