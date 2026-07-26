package ani.rss.service;

import ani.rss.commons.GroupRegexUtils;
import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.dto.MikanScoreResponse;
import ani.rss.exception.UpstreamServiceException;
import ani.rss.persistence.MikanListCacheRepository;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.BgmUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpUtil;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MikanService {
    private static final int MIKAN_REQUEST_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_SCORE_LOOKUP_IDS = 48;
    /**
     * A current Mikan season commonly contains around 80 cards.  Queue the
     * whole normal season during the already-background score warmup so the
     * cards opened after the first two score batches do not start cold.
     * PublicScoreService still caps actual upstream concurrency at 12 mapping
     * and 4 score requests.
     */
    static final int MAX_BACKGROUND_SCORE_WARMUPS_PER_LIST = 96;
    /** A seasonal schedule changes slowly enough to safely reuse it across a short service restart. */
    private static final long SEASON_LIST_CACHE_TTL = TimeUnit.MINUTES.toMillis(10);
    /**
     * A stale seasonal schedule is usable while one bounded background request
     * refreshes it. This avoids making the picker wait on Mikan every time the
     * normal ten-minute cache expires, without presenting yesterday's schedule.
     */
    private static final long STALE_SEASON_LIST_MAX_AGE = TimeUnit.HOURS.toMillis(1);
    private static final long STALE_LIST_MEMORY_TTL = TimeUnit.SECONDS.toMillis(1);
    private static final int STALE_REFRESH_QUEUE_CAPACITY = 8;
    private static final long SEARCH_LIST_CACHE_TTL = TimeUnit.SECONDS.toMillis(45);
    private static final String LIST_CACHE_PREFIX = "mikan:list:";
    private static final Pattern MIKAN_ID = Pattern.compile("\\d+");

    @Resource
    private PublicScoreService publicScoreService;
    @Resource
    private TaskCoordinator taskCoordinator;
    /** Nullable only in isolated unit tests that deliberately avoid SQLite I/O. */
    @Resource
    private MikanListCacheRepository persistentListCache;
    private final ConcurrentMap<String, Object> listLoadLocks = new ConcurrentHashMap<>();
    private final Set<String> staleListRefreshes = ConcurrentHashMap.newKeySet();
    private final ExecutorService staleListRefreshExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(STALE_REFRESH_QUEUE_CAPACITY),
            newDaemonThreadFactory("mikan-list-refresh"),
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final MikanListLoader listLoader;

    public MikanService() {
        this.listLoader = this::search;
    }

    MikanService(PublicScoreService publicScoreService, MikanListLoader listLoader) {
        this(publicScoreService, listLoader, null);
    }

    MikanService(
            PublicScoreService publicScoreService,
            MikanListLoader listLoader,
            MikanListCacheRepository persistentListCache
    ) {
        this.publicScoreService = publicScoreService;
        this.listLoader = listLoader;
        this.persistentListCache = persistentListCache;
    }

    public static String getMikanHost() {
        Config config = ConfigUtil.CONFIG;
        String mikanHost = config.getMikanHost();
        mikanHost = StrUtil.blankToDefault(mikanHost, "https://mikanani.me");
        return mikanHost;
    }

    /**
     * 搜索mikan番剧列表
     *
     * @param text   关键字
     * @param season 集度
     * @return Mikan
     */
    public Mikan list(String text, Mikan.Season season) {
        Mikan mikan = loadCachedList(text, season);
        List<MikanInfo> mikanInfos = Optional.ofNullable(mikan.getWeeks())
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(Mikan.Week::getItems)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .filter(Objects::nonNull)
                .toList();

        // A season must be usable even when the public score cache is cold;
        // cache reads and warmup queueing must not wait for the upstream.
        // Read cached scores and start cold enrichment in one traversal. The
        // previous two-step path consulted the durable mapping cache once for
        // rendering and again for warmup, multiplying SQLite work by the
        // number of cards in a season.
        PublicScoreService.MikanScoreLookup cachedScores =
                publicScoreService.getCachedMikanScoreLookupAndWarm(
                        mikanInfos, MAX_BACKGROUND_SCORE_WARMUPS_PER_LIST);
        applyScores(
                mikan,
                cachedScores.scores(),
                subscribedBgmIds(),
                subscribedMikanIds()
        );

        return mikan;
    }

    public MikanScoreResponse scores(Collection<String> mikanIds) {
        List<MikanInfo> mikanInfos = Optional.ofNullable(mikanIds)
                .orElseGet(List::of)
                .stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(id -> MIKAN_ID.matcher(id).matches())
                .distinct()
                .limit(MAX_SCORE_LOOKUP_IDS)
                .map(id -> new MikanInfo().setUrl(mikanBangumiUrl(id)))
                .toList();

        Map<String, MikanBgm> scores = Map.of();
        Set<String> retryableMikanIds = Set.of();
        if (!mikanInfos.isEmpty()) {
            try {
                PublicScoreService.MikanScoreLookup lookup =
                        publicScoreService.getCachedMikanScoreLookupAndWarm(mikanInfos);
                scores = lookup.scores();
                retryableMikanIds = lookup.retryableMikanIds();
            } catch (RuntimeException e) {
                // Scores are optional and must never turn a usable list into an error.
                log.warn("Unable to enrich the Mikan list with public scores");
                retryableMikanIds = mikanInfos.stream()
                        .map(MikanInfo::getUrl)
                        .map(PublicScoreService::extractMikanId)
                        .filter(StrUtil::isNotBlank)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            }
        }
        return new MikanScoreResponse()
                .setScores(scores)
                .setSubscribedBgmIds(subscribedBgmIds())
                .setRetryableMikanIds(retryableMikanIds);
    }

    private static String mikanBangumiUrl(String id) {
        return resolveMikanUrl("Home/Bangumi/" + id);
    }

    static String resolveMikanUrl(String reference) {
        if (StrUtil.isBlank(reference)) {
            return "";
        }
        try {
            String host = StrUtil.removeSuffix(getMikanHost().trim(), "/") + "/";
            URI resolved = URI.create(host).resolve(reference.trim());
            String scheme = resolved.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return "";
            }
            return resolved.toString();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static Set<String> subscribedBgmIds() {
        return AniUtil.ANI_LIST
                .stream()
                .map(Ani::getBgmUrl)
                .filter(StrUtil::isNotBlank)
                .map(BgmUtil::getSubjectId)
                .collect(Collectors.toSet());
    }

    private static Set<String> subscribedMikanIds() {
        return AniUtil.ANI_LIST
                .stream()
                .map(AniUtil::getBangumiId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());
    }

    static void applyScores(Mikan mikan, Map<String, MikanBgm> mikanBgmMap, Set<String> subscribedBgmIds) {
        applyScores(mikan, mikanBgmMap, subscribedBgmIds, null);
    }

    static void applyScores(
            Mikan mikan,
            Map<String, MikanBgm> mikanBgmMap,
            Set<String> subscribedBgmIds,
            Set<String> subscribedMikanIds) {
        if (mikan == null || mikan.getWeeks() == null) {
            return;
        }
        Map<String, MikanBgm> scores = Optional.ofNullable(mikanBgmMap).orElseGet(Map::of);
        Set<String> subscriptions = Optional.ofNullable(subscribedBgmIds).orElseGet(Set::of);
        Set<String> mikanSubscriptions = Optional.ofNullable(subscribedMikanIds).orElseGet(Set::of);
        List<Mikan.Week> weeks = mikan.getWeeks();
        for (Mikan.Week week : weeks) {
            List<MikanInfo> mikanInfos = week.getItems();
            if (mikanInfos == null) {
                continue;
            }
            for (MikanInfo mikanInfo : mikanInfos) {
                if (mikanInfo == null) {
                    continue;
                }
                String url = mikanInfo.getUrl();
                String mikanId = PublicScoreService.extractMikanId(url);
                if (subscribedMikanIds != null) {
                    mikanInfo.setExists(mikanSubscriptions.contains(mikanId));
                }
                mikanInfo.setScore(0.0);
                if (StrUtil.isBlank(mikanId)) {
                    continue;
                }
                MikanBgm mikanBgm = scores.get(mikanId);
                if (mikanBgm == null) {
                    continue;
                }

                Double score = Optional.ofNullable(mikanBgm.getScore()).orElse(0.0);
                String bgmId = mikanBgm.getBgmId();
                mikanInfo.setScore(score)
                        .setBgmId(bgmId);

                if (subscriptions.contains(bgmId)) {
                    mikanInfo.setExists(true);
                }
            }
            ListUtil.sort(
                    mikanInfos,
                    Comparator.comparingDouble((MikanInfo info) -> Optional.ofNullable(info.getScore()).orElse(0.0))
                            .reversed()
            );
        }
    }

    private Mikan loadCachedList(String text, Mikan.Season season) {
        String normalizedText = StrUtil.blankToDefault(text, "").trim();
        String cacheKey = listCacheKey(normalizedText, season);
        Mikan cached = CacheUtils.get(cacheKey);
        if (cached != null) {
            return copyMikan(cached);
        }
        CachedMikanList persisted = loadPersistentList(cacheKey, normalizedText);
        if (persisted != null) {
            long remaining = Math.max(1, persisted.expiresAt() - System.currentTimeMillis());
            Mikan snapshot = copyMikan(persisted.list());
            CacheUtils.put(cacheKey, snapshot, remaining);
            return copyMikan(snapshot);
        }

        CachedMikanList stale = loadStalePersistentSeasonList(cacheKey, normalizedText);
        if (stale != null) {
            Mikan snapshot = copyMikan(stale.list());
            // Keep the stale entry only long enough to coalesce rapid callers.
            // The refresh itself replaces it with a normal fresh-cache entry.
            CacheUtils.put(cacheKey, snapshot, STALE_LIST_MEMORY_TTL);
            refreshStaleSeasonListAsync(cacheKey, normalizedText, copySeason(season));
            return copyMikan(snapshot);
        }

        return loadListSynchronously(cacheKey, normalizedText, season);
    }

    private Mikan loadListSynchronously(String cacheKey, String text, Mikan.Season season) {
        Object lock = listLoadLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        try {
            synchronized (lock) {
                Mikan cached = CacheUtils.get(cacheKey);
                if (cached != null) {
                    return copyMikan(cached);
                }
                CachedMikanList persisted = loadPersistentList(cacheKey, text);
                if (persisted != null) {
                    long remaining = Math.max(1, persisted.expiresAt() - System.currentTimeMillis());
                    Mikan snapshot = copyMikan(persisted.list());
                    CacheUtils.put(cacheKey, snapshot, remaining);
                    return copyMikan(snapshot);
                }
                return loadAndCacheList(cacheKey, text, season);
            }
        } finally {
            listLoadLocks.remove(cacheKey, lock);
        }
    }

    private Mikan loadAndCacheList(String cacheKey, String text, Mikan.Season season) {
        Mikan loaded = listLoader.load(text, copySeason(season));
        Mikan snapshot = copyMikan(loaded);
        long ttl = listCacheTtl(text);
        CacheUtils.put(cacheKey, snapshot, ttl);
        persistSeasonList(cacheKey, text, snapshot, ttl);
        return copyMikan(snapshot);
    }

    private void refreshStaleSeasonListAsync(String cacheKey, String text, Mikan.Season season) {
        if (!backgroundWorkAllowed() || !staleListRefreshes.add(cacheKey)) {
            return;
        }
        try {
            staleListRefreshExecutor.execute(() -> {
                try {
                    refreshStaleSeasonList(cacheKey, text, season);
                } catch (RuntimeException e) {
                    // The stale snapshot stays available for the next request.
                    // Do not expose upstream URLs or bodies in logs.
                    log.debug("Unable to refresh stale Mikan season list cache");
                } finally {
                    staleListRefreshes.remove(cacheKey);
                }
            });
        } catch (RejectedExecutionException e) {
            staleListRefreshes.remove(cacheKey);
            log.debug("Mikan season list refresh queue is full");
        }
    }

    private void refreshStaleSeasonList(String cacheKey, String text, Mikan.Season season) {
        Object lock = listLoadLocks.computeIfAbsent(cacheKey, ignored -> new Object());
        try {
            synchronized (lock) {
                CachedMikanList fresh = loadPersistentList(cacheKey, text);
                if (fresh != null) {
                    long remaining = Math.max(1, fresh.expiresAt() - System.currentTimeMillis());
                    CacheUtils.put(cacheKey, copyMikan(fresh.list()), remaining);
                    return;
                }
                loadAndCacheList(cacheKey, text, season);
            }
        } finally {
            listLoadLocks.remove(cacheKey, lock);
        }
    }

    private CachedMikanList loadPersistentList(String cacheKey, String text) {
        if (StrUtil.isNotBlank(text) || persistentListCache == null) {
            return null;
        }
        try {
            return persistentListCache.findValid(cacheKey, System.currentTimeMillis())
                    .map(snapshot -> new CachedMikanList(
                            copyMikan(GsonStatic.GSON.fromJson(snapshot.snapshotJson(), Mikan.class)),
                            snapshot.expiresAt()))
                    .orElse(null);
        } catch (RuntimeException e) {
            // A public list cache is an optional acceleration. A corrupt or
            // unavailable record must fall back to Mikan without breaking the picker.
            log.debug("Unable to read durable Mikan season list cache");
            return null;
        }
    }

    private CachedMikanList loadStalePersistentSeasonList(String cacheKey, String text) {
        if (StrUtil.isNotBlank(text) || persistentListCache == null) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            return persistentListCache.findLatest(cacheKey)
                    .filter(snapshot -> snapshot.expiresAt() <= now)
                    .filter(snapshot -> snapshot.expiresAt() >= now - STALE_SEASON_LIST_MAX_AGE)
                    .map(snapshot -> new CachedMikanList(
                            copyMikan(GsonStatic.GSON.fromJson(snapshot.snapshotJson(), Mikan.class)),
                            snapshot.expiresAt()))
                    .orElse(null);
        } catch (RuntimeException e) {
            // A stale cache is an optional fast path. A corrupt record must
            // never prevent the normal synchronous list request.
            log.debug("Unable to read stale Mikan season list cache");
            return null;
        }
    }

    private void persistSeasonList(String cacheKey, String text, Mikan snapshot, long ttl) {
        if (StrUtil.isNotBlank(text) || persistentListCache == null || ttl <= 0) {
            return;
        }
        try {
            persistentListCache.save(cacheKey, GsonStatic.toJson(snapshot), System.currentTimeMillis() + ttl);
        } catch (RuntimeException e) {
            // The live in-memory entry is still valid even when persistence is unavailable.
            log.debug("Unable to save durable Mikan season list cache");
        }
    }

    private static long listCacheTtl(String text) {
        return StrUtil.isBlank(text) ? SEASON_LIST_CACHE_TTL : SEARCH_LIST_CACHE_TTL;
    }

    private boolean backgroundWorkAllowed() {
        if (taskCoordinator == null) {
            return true;
        }
        try {
            taskCoordinator.requireStartAllowed();
            return true;
        } catch (IllegalStateException ignored) {
            return false;
        }
    }

    private static ThreadFactory newDaemonThreadFactory(String namePrefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void stopStaleRefreshExecutor() {
        staleListRefreshExecutor.shutdownNow();
    }

    static String listCacheKey(String text, Mikan.Season season) {
        String seasonKey = season == null
                ? ""
                : String.valueOf(season.getYear()) + "\u0000" + StrUtil.blankToDefault(season.getSeason(), "");
        String source = StrUtil.removeSuffix(getMikanHost().trim(), "/")
                + "\u0000" + text + "\u0000" + seasonKey;
        return LIST_CACHE_PREFIX + SecureUtil.sha256(source);
    }

    private static Mikan.Season copySeason(Mikan.Season season) {
        if (season == null) {
            return new Mikan.Season();
        }
        return new Mikan.Season()
                .setYear(season.getYear())
                .setSeason(season.getSeason())
                .setSeasonLabel(season.getSeasonLabel())
                .setSelect(season.getSelect());
    }

    private static Mikan copyMikan(Mikan mikan) {
        if (mikan == null) {
            throw new IllegalStateException("Mikan list response must not be null");
        }
        return GsonStatic.GSON.fromJson(GsonStatic.toJson(mikan), Mikan.class);
    }

    public Mikan search(String text, Mikan.Season season) {
        if (season == null) {
            season = new Mikan.Season();
        }
        Set<String> bangumiIdSet = AniUtil.ANI_LIST.stream()
                .map(AniUtil::getBangumiId)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toSet());

        Mikan mikan = new Mikan();
        List<Mikan.Week> weeks = new ArrayList<>();
        List<Mikan.Season> seasons = new ArrayList<>();

        String regex = "^id: (\\d+)$";

        if (ReUtil.contains(regex, text)) {
            String mikanId = ReUtil.get(regex, text, 1);

            MikanInfo mikanInfo = getMikanInfo(mikanId);

            weeks.add(
                    new Mikan.Week()
                            .setWeekLabel("Search")
                            .setItems(Collections.singletonList(mikanInfo))
            );

            return mikan
                    .setTotalItem(1)
                    .setWeeks(weeks)
                    .setSeasons(seasons);
        }

        String url = getMikanHost();
        if (StrUtil.isNotBlank(text)) {
            url = url + "/Home/Search?searchstr=" + URLUtil.encodeBlank(text);
        } else {
            Integer year = season.getYear();
            String seasonStr = season.getSeason();
            if (Objects.nonNull(year) && StrUtil.isNotBlank(seasonStr)) {
                url = StrUtil.format(
                        "{}/Home/BangumiCoverFlowByDayOfWeek?year={}&seasonStr={}",
                        url, year, seasonStr
                );
            }
        }

        try {
            HttpReq.get(url)
                    .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                    .then(res -> {
                    HttpReq.assertStatus(res);
                    Document document = Jsoup.parse(res.body());
                    if (document.select(".date-select, .sk-bangumi, .an-ul").isEmpty()) {
                        throw new IllegalStateException("Mikan response does not contain a supported list");
                    }
                    Elements dateSelects = document.select(".date-select");
                    if (!dateSelects.isEmpty()) {
                        Element dateSelect = dateSelects.get(0);
                        String dateText = dateSelects.get(0).select(".date-text").text().trim();
                        Element dropdownMenu = dateSelect.selectFirst(".dropdown-menu");
                        if (dropdownMenu == null) {
                            throw new IllegalStateException("Mikan response is missing the season menu");
                        }
                        for (Element child : dropdownMenu.children()) {
                            Elements seasonItems = child.select("li");
                            for (Element seasonItem : seasonItems.subList(1, seasonItems.size())) {
                                Element a = seasonItem.selectFirst("a");
                                if (a == null) {
                                    throw new IllegalStateException("Mikan response contains a season without a link");
                                }
                                String dataYear = a.attr("data-year");
                                String dataSeason = a.attr("data-season");
                                String selectLabel = StrUtil.format("{} {}", dataYear, dataSeason);
                                seasons.add(
                                        new Mikan.Season()
                                                .setYear(Integer.parseInt(dataYear))
                                                .setSeason(dataSeason)
                                                .setSeasonLabel(selectLabel)
                                                .setSelect(dateText.startsWith(selectLabel))
                                );
                            }
                        }
                    }

                    Function<Element, List<MikanInfo>> get = (el) -> {
                        List<MikanInfo> mikanInfos = new ArrayList<>();
                        if (Objects.isNull(el)) {
                            return mikanInfos;
                        }
                        Elements lis = el.select("li");
                        for (Element li : lis) {
                            Element image = li.selectFirst("span");
                            String img = image == null ? "" : resolveMikanUrl(image.attr("data-src"));
                            Elements aa = li.select("a");
                            if (aa.isEmpty()) {
                                continue;
                            }
                            String href = resolveMikanUrl(aa.get(0).attr("href"));
                            String title = aa.get(0).text();

                            String id = ReUtil.get("\\d+(/)?$", href, 0);
                            id = StrUtil.blankToDefault(id, "");
                            mikanInfos.add(
                                    new MikanInfo()
                                            .setCover(img)
                                            .setTitle(title)
                                            .setUrl(href)
                                            .setExists(bangumiIdSet.contains(id))
                                            .setScore(0.0)
                            );
                        }
                        return mikanInfos;
                    };

                    Elements skBangumis = document.select(".sk-bangumi");

                    if (skBangumis.isEmpty()) {
                        List<MikanInfo> mikanInfos = get.apply(document.selectFirst(".an-ul"));

                        Mikan.Week item = new Mikan.Week();
                        item.setItems(mikanInfos)
                                .setWeekLabel("Search");

                        weeks.add(item);
                    } else {
                        for (Element skBangumi : skBangumis) {
                            List<MikanInfo> mikanInfos = get.apply(skBangumi);
                            if (mikanInfos.isEmpty()) {
                                // 番剧为空
                                continue;
                            }

                            // 星期
                            String label = skBangumi.children().get(0).text().trim();

                            Mikan.Week week = new Mikan.Week();
                            week.setWeekLabel(label)
                                    .setItems(mikanInfos);
                            weeks.add(week);
                        }
                    }
                    });
        } catch (Exception e) {
            log.warn("Mikan list request failed origin:{} type:{}", HttpReq.sanitizeOrigin(url),
                    e.getClass().getSimpleName());
            throw new UpstreamServiceException("Mikan 服务暂时不可用，请检查网络或代理设置后重试", e);
        }

        int totalItems = weeks
                .stream()
                .mapToInt(it -> it.getItems().size())
                .sum();

        return mikan
                .setWeeks(weeks)
                .setTotalItem(totalItems)
                .setSeasons(seasons);
    }

    /**
     * 获取番剧字幕组
     *
     * @param url 链接
     * @return 字幕组列表
     */
    public List<Mikan.Group> getGroups(String url) {
        List<Mikan.Group> groupList = HttpReq.get(url)
                .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    Document document = Jsoup.parse(res.body());
                    List<Mikan.Group> groups = new ArrayList<>();

                    String bgmUrl = "";
                    Elements bangumiInfos = document.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element link = bangumiInfo.selectFirst("a");
                            if (link != null) {
                                bgmUrl = link.attr("href");
                            }
                        }
                    }

                    Elements subgroupTitles = document.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();
                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items)
                                .setBgmUrl(bgmUrl);
                        String label = subgroupText.select("a.subgroup-name").text().trim();
                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");
                        Element anchor = document.selectFirst(id);
                        Element rss = anchor == null ? null : anchor.selectFirst(".mikan-rss");
                        if (rss == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup RSS link");
                        }
                        String attr = rss.attr("href");
                        group.setLabel(label)
                                .setRss(resolveMikanUrl(attr));
                        groups.add(group);
                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();
                        group.setUpdateDay(day);

                        Element subgroupAnchor = document.selectFirst(id);
                        Element table = subgroupAnchor == null ? null : subgroupAnchor.nextElementSibling();
                        Element tbody = table == null ? null : table.selectFirst("tbody");
                        if (tbody == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup table");
                        }
                        for (Element tr : tbody.children()) {
                            String title = tr.select("a").get(0).ownText();
                            String magnet = tr.select("a").get(1).attr("data-clipboard-text");
                            String formatSize = tr.select("td").get(2).text().trim();
                            String dateStr = tr.select("td").get(3).text().trim();

                            String torrent = tr.select("a").get(2).attr("href");

                            items.add(
                                    new Mikan.Item()
                                            .setTitle(title)
                                            .setMagnet(magnet)
                                            .setFormatSize(formatSize)
                                            .setCreatedAt(DateUtil.parse(dateStr))
                                            .setTorrent(resolveMikanUrl(torrent))
                            );
                        }
                    }

                    return groups;
                });


        for (Mikan.Group group : groupList) {
            List<Mikan.Item> items = group.getItems();
            GroupRegex groupRegx = GroupRegexUtils.toGroupRegx(items, Mikan.Item::getTitle);

            group.setGroupRegex(groupRegx);
        }

        return groupList;
    }

    public static MikanInfo getMikanInfo(String bangumiId) {
        String url = mikanBangumiUrl(bangumiId);
        return HttpReq.get(url)
                .timeout(MIKAN_REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    MikanInfo mikanInfo = new MikanInfo();

                    mikanInfo.setUrl(url);

                    Document html = Jsoup.parse(res.body());

                    Element cover = html.selectFirst(".content > img");
                    if (Objects.nonNull(cover)) {
                        mikanInfo.setCover(resolveMikanUrl(cover.attr("src")));
                    }

                    Element bangumiTitle = html.selectFirst(".bangumi-title");
                    if (Objects.nonNull(bangumiTitle)) {
                        mikanInfo.setTitle(bangumiTitle.text().trim());
                    }

                    Elements bangumiInfos = html.select(".bangumi-info");
                    for (Element bangumiInfo : bangumiInfos) {
                        String string = bangumiInfo.ownText();
                        if (string.equals("Bangumi番组计划链接：")) {
                            Element link = bangumiInfo.selectFirst("a");
                            if (link != null) {
                                mikanInfo.setBgmUrl(link.attr("href"));
                            }
                        }
                    }

                    // 获取字幕组
                    List<Mikan.Group> groups = new ArrayList<>();

                    Elements subgroupTitles = html.select(".leftbar-item");

                    for (Element subgroupText : subgroupTitles) {
                        Mikan.Group group = new Mikan.Group();
                        groups.add(group);

                        List<Mikan.Item> items = new ArrayList<>();
                        group.setItems(items);

                        String label = subgroupText.select("a.subgroup-name").text().trim();

                        // id锚点，例如 #213
                        String id = subgroupText.select("a.subgroup-name").attr("data-anchor");

                        Element anchor = html.selectFirst(id);
                        Element rss = anchor == null ? null : anchor.selectFirst(".mikan-rss");
                        if (rss == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup RSS link");
                        }
                        String attr = rss.attr("href");

                        group.setLabel(label)
                                .setSubgroupId(id.replace("#", "").trim())
                                .setRss(resolveMikanUrl(attr));

                        // 字幕组更新日期
                        String day = subgroupText.select(".date").text().trim();

                        group.setUpdateDay(day);

                        Element subgroupAnchor = html.selectFirst(id);
                        Element table = subgroupAnchor == null ? null : subgroupAnchor.nextElementSibling();
                        Element tbody = table == null ? null : table.selectFirst("tbody");
                        if (tbody == null) {
                            throw new IllegalStateException("Mikan response is missing a subgroup table");
                        }
                        for (Element tr : tbody.children()) {
                            String title = tr.select("a").get(0).ownText();
                            String magnet = tr.select("a").get(1).attr("data-clipboard-text");
                            String formatSize = tr.select("td").get(2).text().trim();
                            String dateStr = tr.select("td").get(3).text().trim();

                            String torrent = tr.select("a").get(2).attr("href");

                            items.add(
                                    new Mikan.Item()
                                            .setTitle(title)
                                            .setMagnet(magnet)
                                            .setFormatSize(formatSize)
                                            .setCreatedAt(DateUtil.parse(dateStr))
                                            .setTorrent(resolveMikanUrl(torrent))
                            );
                        }
                    }

                    mikanInfo.setGroups(groups);
                    return mikanInfo;
                });
    }

    public static void getMikanInfo(Ani ani, String subgroupId) {
        String bangumiId = AniUtil.getBangumiId(ani);
        if (StrUtil.isBlank(bangumiId)) {
            return;
        }

        MikanInfo mikanInfo = getMikanInfo(bangumiId);
        Assert.notNull(mikanInfo, "未获取到 Mikan 信息");

        String title = mikanInfo.getTitle();
        String bgmUrl = mikanInfo.getBgmUrl();
        List<Mikan.Group> groups = mikanInfo.getGroups();

        ani
                .setMikanTitle(title)
                .setBgmUrl(bgmUrl);

        for (Mikan.Group group : groups) {
            String id = group.getSubgroupId();
            String label = group.getLabel();
            if (subgroupId.equals(id)) {
                ani.setSubgroup(label);
            }
        }
    }

    /**
     * 从rss中获得字幕组id
     *
     * @param url 链接
     * @return 字幕组id
     */
    public static String getSubgroupId(String url) {
        Map<String, String> decodeParamMap = HttpUtil.decodeParamMap(url, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> entry : decodeParamMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase("subgroupid")) {
                return entry.getValue();
            }
        }
        return "";
    }

    @FunctionalInterface
    interface MikanListLoader {
        Mikan load(String text, Mikan.Season season);
    }

    private record CachedMikanList(Mikan list, long expiresAt) {
    }

}
