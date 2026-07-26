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
import java.util.Set;
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
    static final long BGM_BATCH_TIMEOUT_MILLIS = 12_000;
    static final long MIKAN_MAPPING_BATCH_TIMEOUT_MILLIS = 12_000;
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
     * represented by 0.0 in this response, but are deliberately not cached so
     * a transient upstream outage does not hide a score for ten minutes.
     */
    public Map<String, Double> getBgmScores(Collection<String> subjectIds) {
        return getBgmScoreLookup(subjectIds, deadlineAfter(BGM_BATCH_TIMEOUT_MILLIS)).scores();
    }

    private BgmScoreLookup getBgmScoreLookup(Collection<String> subjectIds, long deadlineNanos) {
        LinkedHashSet<String> ids = normalizedIds(subjectIds);
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        Set<String> retryableBgmIds = new LinkedHashSet<>();

        for (String subjectId : ids) {
            Double cached = CacheUtils.get(BGM_SCORE_CACHE_PREFIX + subjectId);
            if (cached != null) {
                scores.put(subjectId, cached);
            } else {
                missing.put(subjectId, subjectId);
            }
        }

        Map<String, String> attempted = takeFirst(missing, MAX_SCORE_LOOKUPS_PER_BATCH);
        Set<String> completed = new LinkedHashSet<>();
        for (LookupResult<String, Double> result : resolveScoreBounded(attempted, subjectId -> {
            BgmInfo info = bgmInfoLoader.load(subjectId);
            return Optional.ofNullable(info)
                    .map(BgmInfo::getRating)
                    .map(BgmInfo.Rating::getScore)
                    .filter(score -> score > 0)
                    .orElse(0.0);
        }, deadlineNanos)) {
            completed.add(result.key());
            if (!result.completed()) {
                retryableBgmIds.add(result.key());
                continue;
            }
            double score = Optional.ofNullable(result.value()).filter(value -> value > 0).orElse(0.0);
            CacheUtils.put(
                    BGM_SCORE_CACHE_PREFIX + result.key(),
                    score,
                    score > 0 ? SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL
            );
            scores.put(result.key(), score);
        }

        for (String subjectId : attempted.keySet()) {
            if (!completed.contains(subjectId)) {
                // invokeAll cancels queued work once the bounded deadline is
                // reached. It was never a completed zero-score lookup.
                retryableBgmIds.add(subjectId);
            }
        }
        for (String subjectId : ids) {
            scores.putIfAbsent(subjectId, 0.0);
        }
        // Callers are normally capped below this limit, but preserve the
        // distinction if this service is used directly with a larger set.
        for (String subjectId : missing.keySet()) {
            if (!attempted.containsKey(subjectId)) {
                retryableBgmIds.add(subjectId);
            }
        }
        return new BgmScoreLookup(scores, retryableBgmIds);
    }

    /**
     * Resolves Mikan entry ids to their public Bangumi subject ids and scores.
     * A Mikan detail page is only requested when the mapping is not in the
     * local cache and the list response did not already contain a BGM URL.
     */
    public Map<String, MikanBgm> getMikanScores(Collection<MikanInfo> mikanInfos) {
        return getMikanScoreLookup(mikanInfos).scores();
    }

    /**
     * Resolves Mikan scores and reports only entries whose remote lookup was
     * interrupted or rejected by an upstream. Entries without a Bangumi link
     * are deliberately not retryable.
     */
    public MikanScoreLookup getMikanScoreLookup(Collection<MikanInfo> mikanInfos) {
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
        // Resolving Mikan-to-Bangumi links and obtaining Bangumi ratings are
        // independent network stages. A slow Mikan page must not consume the
        // full rating budget for entries whose Bangumi id is already known.
        MikanBgmResolution resolution = resolveMikanBgmIds(
                mikanUrls, deadlineAfter(MIKAN_MAPPING_BATCH_TIMEOUT_MILLIS));
        knownBgmIds.putAll(resolution.bgmIds());
        BgmScoreLookup scoreLookup = getBgmScoreLookup(
                knownBgmIds.values(), deadlineAfter(BGM_BATCH_TIMEOUT_MILLIS));
        Map<String, Double> scores = scoreLookup.scores();
        Set<String> retryableMikanIds = new LinkedHashSet<>(resolution.retryableMikanIds());

        Map<String, MikanBgm> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : knownBgmIds.entrySet()) {
            String mikanId = entry.getKey();
            String bgmId = entry.getValue();
            result.put(mikanId, new MikanBgm(mikanId, bgmId, scores.getOrDefault(bgmId, 0.0)));
            if (scoreLookup.retryableBgmIds().contains(bgmId)) {
                retryableMikanIds.add(mikanId);
            }
        }
        return new MikanScoreLookup(result, retryableMikanIds);
    }

    /**
     * Reads only already-resolved Mikan scores. This is used by the primary
     * season-list request so an uncached public score lookup cannot delay the
     * list itself.
     */
    public Map<String, MikanBgm> getCachedMikanScores(Collection<MikanInfo> mikanInfos) {
        Map<String, MikanBgm> result = new LinkedHashMap<>();
        if (mikanInfos == null) {
            return result;
        }

        for (MikanInfo mikanInfo : mikanInfos) {
            if (mikanInfo == null) {
                continue;
            }
            String mikanId = extractMikanId(mikanInfo.getUrl());
            if (StrUtil.isBlank(mikanId)) {
                continue;
            }

            String bgmId = extractBgmSubjectId(mikanInfo.getBgmUrl());
            if (StrUtil.isBlank(bgmId) && StrUtil.isNotBlank(mikanInfo.getUrl())) {
                bgmId = CacheUtils.get(MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanInfo.getUrl()));
            }
            if (StrUtil.isBlank(bgmId)) {
                continue;
            }

            Double score = CacheUtils.get(BGM_SCORE_CACHE_PREFIX + bgmId);
            if (score != null) {
                result.put(mikanId, new MikanBgm(mikanId, bgmId, score));
            }
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

    private MikanBgmResolution resolveMikanBgmIds(Map<String, String> mikanUrls, long deadlineNanos) {
        Map<String, String> bgmIds = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        Set<String> retryableMikanIds = new LinkedHashSet<>();

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
        Set<String> completed = new LinkedHashSet<>();
        for (LookupResult<String, String> result : resolveStringBounded(
                attempted,
                mikanBgmIdResolver::load,
                deadlineNanos
        )) {
            completed.add(result.key());
            if (!result.completed()) {
                retryableMikanIds.add(result.key());
                continue;
            }
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
        for (String mikanId : attempted.keySet()) {
            if (!completed.contains(mikanId)) {
                retryableMikanIds.add(mikanId);
            }
        }
        for (String mikanId : missing.keySet()) {
            if (!attempted.containsKey(mikanId)) {
                retryableMikanIds.add(mikanId);
            }
        }
        return new MikanBgmResolution(bgmIds, retryableMikanIds);
    }

    private <K> List<LookupResult<K, String>> resolveStringBounded(
            Map<K, String> values,
            StringLoader loader,
            long deadlineNanos
    ) {
        List<Callable<LookupResult<K, String>>> tasks = new ArrayList<>();
        for (Map.Entry<K, String> entry : values.entrySet()) {
            tasks.add(() -> {
                try {
                    return new LookupResult<>(entry.getKey(), loader.load(entry.getValue()), true);
                } catch (Exception ignored) {
                    return new LookupResult<>(entry.getKey(), null, false);
                }
            });
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
            tasks.add(() -> {
                try {
                    return new LookupResult<>(entry.getKey(), loader.load(entry.getValue()), true);
                } catch (Exception ignored) {
                    return new LookupResult<>(entry.getKey(), null, false);
                }
            });
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

    public record MikanScoreLookup(Map<String, MikanBgm> scores, Set<String> retryableMikanIds) {
        public MikanScoreLookup {
            scores = Map.copyOf(scores);
            retryableMikanIds = Set.copyOf(retryableMikanIds);
        }
    }

    private record BgmScoreLookup(Map<String, Double> scores, Set<String> retryableBgmIds) {
    }

    private record MikanBgmResolution(Map<String, String> bgmIds, Set<String> retryableMikanIds) {
    }

    private record LookupResult<K, V>(K key, V value, boolean completed) {
    }
}
