package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.MikanBgm;
import ani.rss.entity.MikanInfo;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves public Bangumi ratings without using an Afdian order or any other
 * account-specific cache.  Lookups are cached locally and deliberately
 * bounded so a fresh Mikan season cannot overload either upstream service.
 */
@Slf4j
@Service
public class PublicScoreService {
    private static final int REQUEST_TIMEOUT_MILLIS = 4_000;
    static final long BGM_BATCH_TIMEOUT_MILLIS = 8_000;
    static final long MIKAN_BATCH_TIMEOUT_MILLIS = 12_000;
    static final int MAX_CONCURRENT_REQUESTS = 8;
    static final int MAX_SCORE_LOOKUPS_PER_BATCH = 64;
    static final int MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH = 48;
    private static final long SCORE_CACHE_TTL = TimeUnit.HOURS.toMillis(6);
    private static final long NEGATIVE_CACHE_TTL = TimeUnit.MINUTES.toMillis(10);
    private static final String BGM_SCORE_CACHE_PREFIX = "public-score:bgm:";
    private static final String MIKAN_BGM_CACHE_PREFIX = "public-score:mikan:";
    private static final String BGM_SUBJECT_API = "https://api.bgm.tv/v0/subjects/";
    private static final String BGM_SUBJECT_CACHE = "https://cache.wushuo.top/bgm/subjects/";
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d+");
    private static final Pattern MIKAN_ID = Pattern.compile("/Home/Bangumi/(\\d+)(?:/)?(?:[?#].*)?$");
    private static final Pattern BGM_SUBJECT_ID = Pattern.compile(
            "https?://(?:bgm\\.tv|bangumi\\.tv|chii\\.in)/subject/(\\d+)(?:/)?(?:[?#].*)?",
            Pattern.CASE_INSENSITIVE
    );

    private final BgmInfoLoader bgmInfoLoader;
    private final MikanBgmIdResolver mikanBgmIdResolver;

    public PublicScoreService() {
        this(PublicScoreService::loadPublicBgmInfo, PublicScoreService::loadMikanBgmId);
    }

    PublicScoreService(BgmInfoLoader bgmInfoLoader, MikanBgmIdResolver mikanBgmIdResolver) {
        this.bgmInfoLoader = bgmInfoLoader;
        this.mikanBgmIdResolver = mikanBgmIdResolver;
    }

    /**
     * Returns a score for every valid requested subject id. Failed lookups are
     * represented by 0.0 so listing a season remains available during an
     * upstream outage.
     */
    public Map<String, Double> getBgmScores(Collection<String> subjectIds) {
        return getBgmScores(subjectIds, deadlineAfter(BGM_BATCH_TIMEOUT_MILLIS));
    }

    private Map<String, Double> getBgmScores(Collection<String> subjectIds, long deadlineNanos) {
        LinkedHashSet<String> ids = normalizedIds(subjectIds);
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();

        for (String subjectId : ids) {
            Double cached = CacheUtils.get(BGM_SCORE_CACHE_PREFIX + subjectId);
            if (cached != null) {
                scores.put(subjectId, cached);
            } else {
                missing.put(subjectId, subjectId);
            }
        }

        Map<String, String> attempted = takeFirst(missing, MAX_SCORE_LOOKUPS_PER_BATCH);
        for (LookupResult<String, Double> result : resolveScoreBounded(attempted, subjectId -> {
            BgmInfo info = bgmInfoLoader.load(subjectId);
            return Optional.ofNullable(info)
                    .map(BgmInfo::getRating)
                    .map(BgmInfo.Rating::getScore)
                    .filter(score -> score > 0)
                    .orElse(0.0);
        }, deadlineNanos)) {
            double score = Optional.ofNullable(result.value()).filter(value -> value > 0).orElse(0.0);
            CacheUtils.put(
                    BGM_SCORE_CACHE_PREFIX + result.key(),
                    score,
                    score > 0 ? SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL
            );
            scores.put(result.key(), score);
        }

        for (String subjectId : attempted.keySet()) {
            if (!scores.containsKey(subjectId)) {
                CacheUtils.put(BGM_SCORE_CACHE_PREFIX + subjectId, 0.0, NEGATIVE_CACHE_TTL);
                scores.put(subjectId, 0.0);
            }
        }

        for (String subjectId : ids) {
            scores.putIfAbsent(subjectId, 0.0);
        }
        return scores;
    }

    /**
     * Resolves Mikan entry ids to their public Bangumi subject ids and scores.
     * A Mikan detail page is only requested when the mapping is not in the
     * local cache and the list response did not already contain a BGM URL.
     */
    public Map<String, MikanBgm> getMikanScores(Collection<MikanInfo> mikanInfos) {
        long deadlineNanos = deadlineAfter(MIKAN_BATCH_TIMEOUT_MILLIS);
        Map<String, String> knownBgmIds = new LinkedHashMap<>();
        Map<String, String> mikanUrls = new LinkedHashMap<>();

        if (mikanInfos != null) {
            for (MikanInfo mikanInfo : mikanInfos) {
                if (mikanInfo == null) {
                    continue;
                }
                String mikanId = extractMikanId(mikanInfo.getUrl());
                if (StrUtil.isBlank(mikanId)) {
                    continue;
                }

                String bgmId = extractBgmSubjectId(mikanInfo.getBgmUrl());
                if (StrUtil.isNotBlank(bgmId)) {
                    knownBgmIds.put(mikanId, bgmId);
                } else if (StrUtil.isNotBlank(mikanInfo.getUrl())) {
                    mikanUrls.putIfAbsent(mikanId, mikanInfo.getUrl());
                }
            }
        }

        mikanUrls.keySet().removeAll(knownBgmIds.keySet());
        Map<String, String> resolvedBgmIds = resolveMikanBgmIds(mikanUrls, deadlineNanos);
        knownBgmIds.putAll(resolvedBgmIds);
        Map<String, Double> scores = getBgmScores(knownBgmIds.values(), deadlineNanos);

        Map<String, MikanBgm> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : knownBgmIds.entrySet()) {
            String mikanId = entry.getKey();
            String bgmId = entry.getValue();
            result.put(mikanId, new MikanBgm(mikanId, bgmId, scores.getOrDefault(bgmId, 0.0)));
        }
        return result;
    }

    static String extractMikanId(String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        Matcher matcher = MIKAN_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String extractBgmSubjectId(String url) {
        if (StrUtil.isBlank(url)) {
            return "";
        }
        if (NUMERIC_ID.matcher(url).matches()) {
            return url;
        }
        Matcher matcher = BGM_SUBJECT_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : "";
    }

    private Map<String, String> resolveMikanBgmIds(Map<String, String> mikanUrls, long deadlineNanos) {
        Map<String, String> bgmIds = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : mikanUrls.entrySet()) {
            String mikanId = entry.getKey();
            String url = entry.getValue();
            String cacheKey = MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(url);
            String cached = CacheUtils.get(cacheKey);
            if (cached != null) {
                if (StrUtil.isNotBlank(cached)) {
                    bgmIds.put(mikanId, cached);
                }
            } else {
                missing.put(mikanId, url);
            }
        }

        Map<String, String> attempted = takeFirst(missing, MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH);
        for (LookupResult<String, String> result : resolveStringBounded(
                attempted,
                mikanBgmIdResolver::load,
                deadlineNanos
        )) {
            String bgmId = extractBgmSubjectId(result.value());
            String cacheKey = MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrls.get(result.key()));
            CacheUtils.put(
                    cacheKey,
                    bgmId,
                    StrUtil.isNotBlank(bgmId) ? SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL
            );
            if (StrUtil.isNotBlank(bgmId)) {
                bgmIds.put(result.key(), bgmId);
            }
        }
        for (Map.Entry<String, String> entry : attempted.entrySet()) {
            if (!bgmIds.containsKey(entry.getKey())) {
                CacheUtils.put(
                        MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(entry.getValue()),
                        "",
                        NEGATIVE_CACHE_TTL
                );
            }
        }
        return bgmIds;
    }

    private <K> List<LookupResult<K, String>> resolveStringBounded(
            Map<K, String> values,
            StringLoader loader,
            long deadlineNanos
    ) {
        List<Callable<LookupResult<K, String>>> tasks = new ArrayList<>();
        for (Map.Entry<K, String> entry : values.entrySet()) {
            tasks.add(() -> new LookupResult<>(entry.getKey(), loader.load(entry.getValue())));
        }
        return invokeBounded(tasks, deadlineNanos);
    }

    private <K> List<LookupResult<K, Double>> resolveScoreBounded(
            Map<K, String> values,
            ScoreLoader loader,
            long deadlineNanos
    ) {
        List<Callable<LookupResult<K, Double>>> tasks = new ArrayList<>();
        for (Map.Entry<K, String> entry : values.entrySet()) {
            tasks.add(() -> new LookupResult<>(entry.getKey(), loader.load(entry.getValue())));
        }
        return invokeBounded(tasks, deadlineNanos);
    }

    private <K, V> List<LookupResult<K, V>> invokeBounded(
            List<Callable<LookupResult<K, V>>> tasks,
            long deadlineNanos
    ) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return List.of();
        }
        long timeoutMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(MAX_CONCURRENT_REQUESTS, tasks.size()));
        try {
            List<Future<LookupResult<K, V>>> futures = executor.invokeAll(
                    tasks,
                    timeoutMillis,
                    TimeUnit.MILLISECONDS
            );
            List<LookupResult<K, V>> results = new ArrayList<>();
            for (Future<LookupResult<K, V>> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (Exception ignored) {
                    // A score is optional; one malformed or unreachable subject must not break a list.
                }
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            executor.shutdownNow();
        }
    }

    private static long deadlineAfter(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static <K, V> Map<K, V> takeFirst(Map<K, V> values, int limit) {
        Map<K, V> result = new LinkedHashMap<>();
        for (Map.Entry<K, V> entry : values.entrySet()) {
            if (result.size() >= limit) {
                break;
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static LinkedHashSet<String> normalizedIds(Collection<String> subjectIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (subjectIds == null) {
            return ids;
        }
        for (String subjectId : subjectIds) {
            if (subjectId != null && NUMERIC_ID.matcher(subjectId).matches()) {
                ids.add(subjectId);
            }
        }
        return ids;
    }

    private static BgmInfo loadPublicBgmInfo(String subjectId) throws Exception {
        Exception firstFailure = null;
        for (String endpoint : List.of(BGM_SUBJECT_API, BGM_SUBJECT_CACHE)) {
            try {
                return HttpReq.get(endpoint + subjectId)
                        .timeout(REQUEST_TIMEOUT_MILLIS)
                        .thenFunction(res -> {
                            HttpReq.assertStatus(res);
                            return GsonStatic.fromJson(res.body(), BgmInfo.class);
                        });
            } catch (Exception e) {
                firstFailure = e;
            }
        }
        log.debug("Public score lookup failed for BGM subject {}", subjectId);
        throw new IllegalStateException("Unable to load public Bangumi subject", firstFailure);
    }

    private static String loadMikanBgmId(String mikanUrl) throws Exception {
        Document document = HttpReq.get(mikanUrl)
                .timeout(REQUEST_TIMEOUT_MILLIS)
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    return Jsoup.parse(res.body(), mikanUrl);
                });

        for (Element link : document.select("a[href]")) {
            String subjectId = extractBgmSubjectId(link.absUrl("href"));
            if (StrUtil.isNotBlank(subjectId)) {
                return subjectId;
            }
        }
        return extractBgmSubjectId(document.html());
    }

    @FunctionalInterface
    interface BgmInfoLoader {
        BgmInfo load(String subjectId) throws Exception;
    }

    @FunctionalInterface
    interface MikanBgmIdResolver {
        String load(String mikanUrl) throws Exception;
    }

    @FunctionalInterface
    private interface StringLoader {
        String load(String value) throws Exception;
    }

    @FunctionalInterface
    private interface ScoreLoader {
        Double load(String value) throws Exception;
    }

    private record LookupResult<K, V>(K key, V value) {
    }
}
