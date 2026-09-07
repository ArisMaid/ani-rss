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
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnimeGardenService {
    private static final String HOST = "https://api.animes.garden";
    private static final int ANIME_GARDEN_REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_SUBJECT_SNAPSHOTS = 32;
    private static final int MAX_SUBJECT_IDS_PER_SNAPSHOT = 10_000;
    private static final int MAX_SUBJECT_ID_REFERENCES = 100_000;
    private static final long SUBJECT_SNAPSHOT_TTL_MILLIS = 10 * 60 * 1000L;

    @Resource
    private CacheService cacheService;

    @Resource
    private PublicScoreService publicScoreService;

    private final SubjectLoader subjectLoader;
    private final BgmInfoLoader bgmInfoLoader;
    private final LongSupplier clock;
    /**
     * A list request is a separate authorization snapshot. Keeping a small,
     * expiring history means an enrichment request from one browser tab is
     * not invalidated by a list request from another tab.
     */
    private final Object subjectSnapshotLock = new Object();
    private final LinkedHashMap<String, SubjectSnapshot> subjectSnapshots = new LinkedHashMap<>();
    private long subjectSnapshotSequence;

    public AnimeGardenService() {
        this(AnimeGardenService::loadSubjectsFromUpstream, null, null,
                AnimeGardenService::loadBgmInfoFromUpstream, System::currentTimeMillis);
    }

    AnimeGardenService(SubjectLoader subjectLoader) {
        this(subjectLoader, null, null, AnimeGardenService::loadBgmInfoFromUpstream,
                System::currentTimeMillis);
    }

    AnimeGardenService(
            SubjectLoader subjectLoader,
            CacheService cacheService,
            PublicScoreService publicScoreService
    ) {
        this(subjectLoader, cacheService, publicScoreService,
                AnimeGardenService::loadBgmInfoFromUpstream, System::currentTimeMillis);
    }

    AnimeGardenService(
            SubjectLoader subjectLoader,
            CacheService cacheService,
            PublicScoreService publicScoreService,
            BgmInfoLoader bgmInfoLoader
    ) {
        this(subjectLoader, cacheService, publicScoreService, bgmInfoLoader,
                System::currentTimeMillis);
    }

    AnimeGardenService(
            SubjectLoader subjectLoader,
            CacheService cacheService,
            PublicScoreService publicScoreService,
            BgmInfoLoader bgmInfoLoader,
            LongSupplier clock
    ) {
        this.subjectLoader = subjectLoader;
        this.bgmInfoLoader = bgmInfoLoader;
        this.cacheService = cacheService;
        this.publicScoreService = publicScoreService;
        this.clock = Objects.requireNonNull(clock);
    }

    public List<AnimeGarden.Week> list(String bgmUrl) {
        List<AnimeGarden.Week> weekList = new ArrayList<>();

        if (StrUtil.isNotBlank(bgmUrl)) {
            AnimeGarden.Week week = new AnimeGarden.Week();
            weekList.add(week);

            String bgmId = BgmUtil.getSubjectId(bgmUrl);
            BgmInfo bgmInfo;
            try {
                bgmInfo = bgmInfoLoader.load(bgmId);
            } catch (Exception e) {
                throw new IllegalStateException("AnimeGarden subject lookup failed", e);
            }
            String name = BgmUtil.getFinalName(bgmInfo);
            String cover = Optional.ofNullable(bgmInfo.getImages())
                    .map(BgmInfo.Images::getSmall)
                    .orElse("");
            Double score = Optional.ofNullable(bgmInfo.getRating())
                    .map(BgmInfo.Rating::getScore)
                    .filter(value -> Double.isFinite(value) && value >= 0)
                    .orElse(null);

            AnimeGarden.Subject subject = new AnimeGarden.Subject();
            if (isValidSubjectId(bgmId)) {
                rememberSubjectSnapshot(bgmUrl, List.of(bgmId));
            }
            subject.setName(name)
                    .setId(bgmId)
                    .setCover(cover)
                    .setExists(true);
            if (score != null) {
                subject.setScore(score);
            }

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
            subjectList = subjectLoader.load();
        } catch (Exception e) {
            log.warn("AnimeGarden subject request failed: {}", e.getMessage());
            throw new IllegalStateException("AnimeGarden subject request failed", e);
        }
        if (subjectList == null) {
            throw new IllegalStateException("AnimeGarden subject response was empty or malformed");
        }

        rememberSubjectSnapshot(bgmUrl, subjectList.stream()
                .map(AnimeGarden.Subject::getId)
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
                    subject
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
        List<String> requested = Optional.ofNullable(subjectIds)
                .orElseGet(List::of)
                .stream()
                .map(id -> id == null ? null : id.trim())
                .toList();
        if (requested.size() > 48) {
            throw new IllegalArgumentException("一次最多补载 48 个 AnimeGarden subject");
        }
        if (requested.stream().anyMatch(id -> !isValidSubjectId(id))) {
            throw new IllegalArgumentException("AnimeGarden subject 必须是已加载列表中的数字 ID");
        }
        Set<String> acceptedSubjectIds = acceptedSubjectIds();
        LinkedHashSet<String> ids = requested.stream()
                .filter(acceptedSubjectIds::contains)
                .distinct()
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (ids.size() != requested.stream().distinct().count()) {
            throw new ani.rss.exception.ApiProblemException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "ANIME_GARDEN_LIST_EXPIRED",
                    "AnimeGarden 列表快照已过期或已被淘汰，请重新加载列表",
                    null,
                    Map.of("reload", "animeGardenList"));
        }

        if (ids.isEmpty()) {
            return new AnimeGarden.EnrichmentResponse()
                    .setSubjects(new LinkedHashMap<>())
                    .setRetryableSubjectIds(new ArrayList<>());
        }

        CacheService.CoverLookup coverLookup = cacheService.getBgmCoverForEnrichmentSnapshot();
        JsonObject coverIndex = coverLookup.entries();
        PublicScoreService.BgmScoreLookup scoreLookup = publicScoreService.getCachedBgmScoresAndWarm(ids);
        Map<String, AnimeGarden.Enrichment> subjects = new LinkedHashMap<>();
        LinkedHashSet<String> retryable = new LinkedHashSet<>(scoreLookup.retryableSubjectIds());
        boolean coverIndexPending = !coverLookup.loaded()
                || coverLookup.expiresAt() <= System.currentTimeMillis();
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

    private static boolean isValidSubjectId(String id) {
        return id != null
                && !id.isBlank()
                && id.length() <= 20
                && id.chars().allMatch(character -> character >= '0' && character <= '9');
    }

    private void rememberSubjectSnapshot(String query, Collection<String> subjectIds) {
        LinkedHashSet<String> ids = Optional.ofNullable(subjectIds)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(AnimeGardenService::isValidSubjectId)
                .distinct()
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return;
        }
        if (ids.size() > MAX_SUBJECT_IDS_PER_SNAPSHOT) {
            throw new IllegalStateException("AnimeGarden 列表快照超过 "
                    + MAX_SUBJECT_IDS_PER_SNAPSHOT + " 个 subject，拒绝静默截断");
        }
        String queryKey = StrUtil.blankToDefault(query, "__default__");
        long now = clock.getAsLong();
        synchronized (subjectSnapshotLock) {
            purgeSubjectSnapshots(now);
            String key = queryKey + "#" + (++subjectSnapshotSequence);
            subjectSnapshots.put(key, new SubjectSnapshot(ids, now));
            while (subjectSnapshots.size() > MAX_SUBJECT_SNAPSHOTS
                    || totalSubjectIdReferences() > MAX_SUBJECT_ID_REFERENCES) {
                subjectSnapshots.remove(subjectSnapshots.keySet().iterator().next());
            }
        }
    }

    private Set<String> acceptedSubjectIds() {
        long now = clock.getAsLong();
        synchronized (subjectSnapshotLock) {
            purgeSubjectSnapshots(now);
            LinkedHashSet<String> accepted = new LinkedHashSet<>();
            subjectSnapshots.values().forEach(snapshot -> accepted.addAll(snapshot.ids()));
            return accepted;
        }
    }

    private void purgeSubjectSnapshots(long now) {
        subjectSnapshots.entrySet().removeIf(entry ->
                now - entry.getValue().createdAt() >= SUBJECT_SNAPSHOT_TTL_MILLIS);
    }

    private int totalSubjectIdReferences() {
        return subjectSnapshots.values().stream()
                .mapToInt(snapshot -> snapshot.ids().size())
                .sum();
    }

    private static List<AnimeGarden.Subject> loadSubjectsFromUpstream() throws Exception {
        return HttpReq.get(HOST + "/subjects")
                .timeout(ANIME_GARDEN_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    JsonObject jsonObject = GsonStatic.fromJson(res.body(), JsonObject.class);
                    JsonArray subjects = jsonObject.getAsJsonArray("subjects");
                    return GsonStatic.fromJsonList(subjects, AnimeGarden.Subject.class);
                });
    }

    private static BgmInfo loadBgmInfoFromUpstream(String subjectId) {
        return BgmUtil.getBgmInfo(subjectId);
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
            log.warn("AnimeGarden group request failed: {}", e.getMessage());
            throw new IllegalStateException("AnimeGarden group request failed", e);
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

    @FunctionalInterface
    interface SubjectLoader {
        List<AnimeGarden.Subject> load() throws Exception;
    }

    @FunctionalInterface
    interface BgmInfoLoader {
        BgmInfo load(String subjectId) throws Exception;
    }

    private record SubjectSnapshot(Set<String> ids, long createdAt) {
        private SubjectSnapshot {
            ids = Set.copyOf(ids);
        }
    }
}
