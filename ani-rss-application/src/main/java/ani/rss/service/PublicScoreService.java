package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.BgmInfo;
import ani.rss.entity.MikanBgm;
import ani.rss.entity.MikanInfo;
import ani.rss.persistence.PublicScoreCacheRepository;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpResponse;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
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
    /** Mikan emits its canonical Bangumi link near the page header. */
    static final int MIKAN_MAPPING_EARLY_SCAN_BYTES = 32 * 1024;
    private static final int MIKAN_MAPPING_SCAN_BUFFER_BYTES = 4 * 1024;
    static final long BGM_BATCH_TIMEOUT_MILLIS = 12_000;
    static final long MIKAN_MAPPING_BATCH_TIMEOUT_MILLIS = 12_000;
    static final int MAX_CONCURRENT_REQUESTS = 16;
    /**
     * Mikan detail pages now stop after their small header. Keep four slots
     * reserved so completed mappings can turn into visible scores immediately
     * instead of waiting behind the remaining seasonal detail pages.
     */
    private static final int MAX_MAPPING_WARMUP_WORKERS = MAX_CONCURRENT_REQUESTS - 4;
    private static final int MAX_SCORE_WARMUP_WORKERS = 4;
    private static final long WARMUP_QUEUE_TIMEOUT_MILLIS = 12_000;
    private static final long WARMUP_FAILURE_RETRY_DELAY_MILLIS = 500;
    static final int MAX_SCORE_LOOKUPS_PER_BATCH = 64;
    static final int MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH = 48;
    private static final long SCORE_CACHE_TTL = TimeUnit.HOURS.toMillis(6);
    private static final long NEGATIVE_CACHE_TTL = TimeUnit.MINUTES.toMillis(10);
    private static final long PERSISTENT_MAPPING_CACHE_TTL = TimeUnit.DAYS.toMillis(14);
    private static final long PERSISTENT_SCORE_CACHE_TTL = TimeUnit.HOURS.toMillis(12);
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
    /** Nullable only in isolated unit tests that deliberately avoid SQLite I/O. */
    private final PublicScoreCacheRepository persistentCache;
    @Resource
    private TaskCoordinator taskCoordinator;
    /** Limits all concurrent score-related upstream calls for this service instance. */
    private final Semaphore upstreamRequests = new Semaphore(MAX_CONCURRENT_REQUESTS, true);
    /** Coalesces repeated cold Mikan detail-page lookups across score batches. */
    private final ConcurrentMap<String, CompletableFuture<String>> mikanMappingFlights = new ConcurrentHashMap<>();
    /** Coalesces repeated cold Bangumi score lookups across score batches. */
    private final ConcurrentMap<String, CompletableFuture<Double>> bgmScoreFlights = new ConcurrentHashMap<>();
    /**
     * Detail mappings and score requests use distinct bounded queues.  This is
     * intentional: a slow seasonal Mikan batch must leave room for the cheap
     * Bangumi request that follows a mapping which has just completed.
     */
    private final ExecutorService mappingWarmupExecutor = newWarmupExecutor(
            MAX_MAPPING_WARMUP_WORKERS, "public-score-mapping");
    private final ExecutorService scoreWarmupExecutor = newWarmupExecutor(
            MAX_SCORE_WARMUP_WORKERS, "public-score-rating");
    /** Avoid restarting a failed optional lookup for every rapid client poll. */
    private final ConcurrentMap<String, Long> warmupRetryAfterNanos = new ConcurrentHashMap<>();

    public PublicScoreService() {
        this(PublicScoreService::loadPublicBgmInfo, PublicScoreService::loadMikanBgmId,
                new PublicScoreCacheRepository());
    }

    PublicScoreService(BgmInfoLoader bgmInfoLoader, MikanBgmIdResolver mikanBgmIdResolver) {
        this(bgmInfoLoader, mikanBgmIdResolver, null);
    }

    PublicScoreService(
            BgmInfoLoader bgmInfoLoader,
            MikanBgmIdResolver mikanBgmIdResolver,
            PublicScoreCacheRepository persistentCache
    ) {
        this.bgmInfoLoader = bgmInfoLoader;
        this.mikanBgmIdResolver = mikanBgmIdResolver;
        this.persistentCache = persistentCache;
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
        Map<String, Double> cachedScores = cachedBgmScores(ids);

        for (String subjectId : ids) {
            if (cachedScores.containsKey(subjectId)) {
                scores.put(subjectId, cachedScores.get(subjectId));
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
     * Returns the score cache immediately and starts any cold work in the
     * background.  The caller receives unfinished entries in
     * {@code retryableMikanIds}; a later, cheap poll reads the completed cache.
     *
     * <p>This is deliberately separate from {@link #getMikanScoreLookup(Collection)}:
     * direct callers may still request a synchronous bounded lookup, while the
     * Mikan picker can render and progressively enrich a cold season without a
     * long-lived HTTP request.</p>
     */
    public MikanScoreLookup getCachedMikanScoreLookupAndWarm(Collection<MikanInfo> mikanInfos) {
        return getCachedMikanScoreLookupAndWarm(mikanInfos, Integer.MAX_VALUE);
    }

    /**
     * Same cache read as {@link #getCachedMikanScoreLookupAndWarm(Collection)}
     * with a bounded number of cold items allowed to enter the warmup queue.
     * Cached scores are still returned for the complete collection.
     */
    MikanScoreLookup getCachedMikanScoreLookupAndWarm(
            Collection<MikanInfo> mikanInfos, int maxColdWarmups) {
        Map<String, MikanBgm> scores = new LinkedHashMap<>();
        Set<String> retryableMikanIds = new LinkedHashSet<>();
        if (mikanInfos == null) {
            return new MikanScoreLookup(scores, retryableMikanIds);
        }
        int remainingColdWarmups = Math.max(0, maxColdWarmups);

        Map<String, String> knownBgmIds = new LinkedHashMap<>();
        Map<String, String> mikanUrls = new LinkedHashMap<>();
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

        mikanUrls.keySet().removeAll(knownBgmIds.keySet());
        Map<String, String> cachedMappings = cachedMikanMappings(mikanUrls);
        for (Map.Entry<String, String> entry : mikanUrls.entrySet()) {
            String mikanId = entry.getKey();
            if (!cachedMappings.containsKey(mikanId)) {
                retryableMikanIds.add(mikanId);
                // The bulk cache read above already proved that this mapping
                // is absent, so avoid immediately taking the SQLite lock a
                // second time for the same picker card.
                if (remainingColdWarmups > 0) {
                    warmMikanMappingAndScore(entry.getValue(), true);
                    remainingColdWarmups--;
                }
                continue;
            }
            String bgmId = extractBgmSubjectId(cachedMappings.get(mikanId));
            if (StrUtil.isNotBlank(bgmId)) {
                knownBgmIds.put(mikanId, bgmId);
            }
            // A completed lookup with no Bangumi link is cached briefly and
            // is not an upstream outage worth polling again.
        }

        Map<String, Double> cachedScores = cachedBgmScores(knownBgmIds.values());
        for (Map.Entry<String, String> entry : knownBgmIds.entrySet()) {
            String mikanId = entry.getKey();
            String bgmId = entry.getValue();
            if (cachedScores.containsKey(bgmId)) {
                scores.put(mikanId, new MikanBgm(mikanId, bgmId, cachedScores.get(bgmId)));
                continue;
            }
            retryableMikanIds.add(mikanId);
            if (remainingColdWarmups > 0) {
                warmBgmScore(bgmId);
                remainingColdWarmups--;
            }
        }
        return new MikanScoreLookup(scores, retryableMikanIds);
    }

    /** Starts bounded background enrichment without retaining a mutable caller collection. */
    public void warmMikanScores(Collection<MikanInfo> mikanInfos) {
        if (mikanInfos == null) {
            return;
        }
        getCachedMikanScoreLookupAndWarm(mikanInfos.stream()
                .filter(java.util.Objects::nonNull)
                .limit(MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH)
                .toList());
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

        Map<String, String> knownBgmIds = new LinkedHashMap<>();
        Map<String, String> mikanUrls = new LinkedHashMap<>();
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

        mikanUrls.keySet().removeAll(knownBgmIds.keySet());
        for (Map.Entry<String, String> entry : cachedMikanMappings(mikanUrls).entrySet()) {
            String bgmId = extractBgmSubjectId(entry.getValue());
            if (StrUtil.isNotBlank(bgmId)) {
                knownBgmIds.put(entry.getKey(), bgmId);
            }
        }

        Map<String, Double> cachedScores = cachedBgmScores(knownBgmIds.values());
        for (Map.Entry<String, String> entry : knownBgmIds.entrySet()) {
            String bgmId = entry.getValue();
            if (cachedScores.containsKey(bgmId)) {
                result.put(entry.getKey(), new MikanBgm(
                        entry.getKey(), bgmId, cachedScores.get(bgmId)));
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
        Map<String, String> cachedMappings = cachedMikanMappings(mikanUrls);

        for (Map.Entry<String, String> entry : mikanUrls.entrySet()) {
            String mikanId = entry.getKey();
            if (cachedMappings.containsKey(mikanId)) {
                String cached = cachedMappings.get(mikanId);
                String bgmId = extractBgmSubjectId(cached);
                if (StrUtil.isNotBlank(bgmId)) {
                    bgmIds.put(mikanId, bgmId);
                }
            } else {
                missing.put(mikanId, entry.getValue());
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
            String flightKey = MIKAN_BGM_CACHE_PREFIX + "flight:" + SecureUtil.sha256(entry.getValue());
            tasks.add(() -> loadSingleFlight(
                    entry.getKey(),
                    flightKey,
                    () -> loadAndCacheMikanMapping(entry.getValue(), loader),
                    mikanMappingFlights,
                    deadlineNanos
            ));
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
            String flightKey = BGM_SCORE_CACHE_PREFIX + "flight:" + entry.getValue();
            tasks.add(() -> loadSingleFlight(
                    entry.getKey(),
                    flightKey,
                    () -> loadAndCacheBgmScore(entry.getValue(), loader),
                    bgmScoreFlights,
                    deadlineNanos
            ));
        }
        return invokeBounded(tasks, deadlineNanos);
    }

    private <K, V> LookupResult<K, V> loadSingleFlight(
            K key,
            String flightKey,
            Callable<V> loader,
            ConcurrentMap<String, CompletableFuture<V>> flights,
            long deadlineNanos
    ) {
        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> shared = flights.putIfAbsent(flightKey, created);
        if (shared == null) {
            try {
                V value = callUpstream(loader, deadlineNanos);
                created.complete(value);
                return new LookupResult<>(key, value, true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                created.completeExceptionally(e);
                return new LookupResult<>(key, null, false);
            } catch (Exception e) {
                created.completeExceptionally(e);
                return new LookupResult<>(key, null, false);
            } finally {
                flights.remove(flightKey, created);
            }
        }

        try {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                return new LookupResult<>(key, null, false);
            }
            return new LookupResult<>(key, shared.get(remainingNanos, TimeUnit.NANOSECONDS), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new LookupResult<>(key, null, false);
        } catch (Exception ignored) {
            return new LookupResult<>(key, null, false);
        }
    }

    private <V> V callUpstream(Callable<V> loader, long deadlineNanos) throws Exception {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0 || !upstreamRequests.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("public score lookup queue timed out");
        }
        try {
            return loader.call();
        } finally {
            upstreamRequests.release();
        }
    }

    private String loadAndCacheMikanMapping(String mikanUrl, StringLoader loader) throws Exception {
        String value = loader.load(mikanUrl);
        String bgmId = extractBgmSubjectId(value);
        CacheUtils.put(
                MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl),
                bgmId,
                StrUtil.isNotBlank(bgmId) ? SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL
        );
        persistMikanMapping(extractMikanId(mikanUrl), bgmId,
                StrUtil.isNotBlank(bgmId) ? PERSISTENT_MAPPING_CACHE_TTL : NEGATIVE_CACHE_TTL);
        return value;
    }

    private Double loadAndCacheBgmScore(String subjectId, ScoreLoader loader) throws Exception {
        double score = Optional.ofNullable(loader.load(subjectId)).filter(value -> value > 0).orElse(0.0);
        CacheUtils.put(
                BGM_SCORE_CACHE_PREFIX + subjectId,
                score,
                score > 0 ? SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL
        );
        persistBgmScore(subjectId, score, score > 0 ? PERSISTENT_SCORE_CACHE_TTL : NEGATIVE_CACHE_TTL);
        return score;
    }

    /**
     * Bulk-primes the in-memory Mikan mapping cache from SQLite.  A seasonal
     * list often has dozens of cards; taking the database lock once is much
     * faster than checking its durable mapping record once per card.
     */
    private Map<String, String> cachedMikanMappings(Map<String, String> mikanUrls) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        if (mikanUrls == null || mikanUrls.isEmpty()) {
            return result;
        }

        for (Map.Entry<String, String> entry : mikanUrls.entrySet()) {
            String mikanId = entry.getKey();
            String mikanUrl = entry.getValue();
            if (StrUtil.isBlank(mikanId) || StrUtil.isBlank(mikanUrl)) {
                continue;
            }
            String cached = CacheUtils.get(MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl));
            if (cached != null) {
                result.put(mikanId, cached);
            } else {
                missing.put(mikanId, mikanUrl);
            }
        }
        if (missing.isEmpty() || persistentCache == null || !backgroundWorkAllowed()) {
            return result;
        }

        try {
            long now = System.currentTimeMillis();
            Map<String, PublicScoreCacheRepository.MikanMapping> persisted =
                    persistentCache.findMikanMappings(missing.keySet(), now);
            for (Map.Entry<String, PublicScoreCacheRepository.MikanMapping> entry : persisted.entrySet()) {
                String mikanUrl = missing.get(entry.getKey());
                PublicScoreCacheRepository.MikanMapping mapping = entry.getValue();
                if (mikanUrl == null || mapping == null) {
                    continue;
                }
                long remaining = mapping.expiresAt() - now;
                if (remaining <= 0) {
                    continue;
                }
                String value = StrUtil.blankToDefault(mapping.bgmId(), "");
                CacheUtils.put(MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl), value, remaining);
                result.put(entry.getKey(), value);
            }
        } catch (RuntimeException e) {
            // A durable cache failure is never allowed to make an optional
            // public score lookup fail; the normal upstream path still works.
            log.debug("Unable to read durable Mikan score mapping cache");
        }
        return result;
    }

    /** See {@link #cachedMikanMappings(Map)} for why this is batch-oriented. */
    private Map<String, Double> cachedBgmScores(Collection<String> bgmIds) {
        LinkedHashSet<String> ids = normalizedIds(bgmIds);
        Map<String, Double> result = new LinkedHashMap<>();
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        for (String bgmId : ids) {
            Double cached = CacheUtils.get(BGM_SCORE_CACHE_PREFIX + bgmId);
            if (cached != null) {
                result.put(bgmId, cached);
            } else {
                missing.add(bgmId);
            }
        }
        if (missing.isEmpty() || persistentCache == null || !backgroundWorkAllowed()) {
            return result;
        }

        try {
            long now = System.currentTimeMillis();
            Map<String, PublicScoreCacheRepository.BgmScore> persisted =
                    persistentCache.findBgmScores(missing, now);
            for (Map.Entry<String, PublicScoreCacheRepository.BgmScore> entry : persisted.entrySet()) {
                PublicScoreCacheRepository.BgmScore score = entry.getValue();
                if (score == null) {
                    continue;
                }
                long remaining = score.expiresAt() - now;
                if (remaining <= 0) {
                    continue;
                }
                CacheUtils.put(BGM_SCORE_CACHE_PREFIX + entry.getKey(), score.score(), remaining);
                result.put(entry.getKey(), score.score());
            }
        } catch (RuntimeException e) {
            log.debug("Unable to read durable Bangumi score cache");
        }
        return result;
    }

    private String cachedMikanMapping(String mikanId, String mikanUrl) {
        if (StrUtil.isBlank(mikanUrl)) {
            return null;
        }
        String cacheKey = MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl);
        String cached = CacheUtils.get(cacheKey);
        if (cached != null || persistentCache == null || StrUtil.isBlank(mikanId) || !backgroundWorkAllowed()) {
            return cached;
        }
        try {
            long now = System.currentTimeMillis();
            Optional<PublicScoreCacheRepository.MikanMapping> persisted =
                    persistentCache.findMikanMapping(mikanId, now);
            if (persisted.isEmpty()) {
                return null;
            }
            PublicScoreCacheRepository.MikanMapping mapping = persisted.get();
            long remaining = mapping.expiresAt() - now;
            if (remaining <= 0) {
                return null;
            }
            String value = StrUtil.blankToDefault(mapping.bgmId(), "");
            CacheUtils.put(cacheKey, value, remaining);
            return value;
        } catch (RuntimeException e) {
            // A durable cache failure is never allowed to make an optional
            // public score lookup fail; the normal upstream path still works.
            log.debug("Unable to read durable Mikan score mapping cache");
            return null;
        }
    }

    private Double cachedBgmScore(String bgmId) {
        if (StrUtil.isBlank(bgmId)) {
            return null;
        }
        String cacheKey = BGM_SCORE_CACHE_PREFIX + bgmId;
        Double cached = CacheUtils.get(cacheKey);
        if (cached != null || persistentCache == null || !backgroundWorkAllowed()) {
            return cached;
        }
        try {
            long now = System.currentTimeMillis();
            Optional<PublicScoreCacheRepository.BgmScore> persisted = persistentCache.findBgmScore(bgmId, now);
            if (persisted.isEmpty()) {
                return null;
            }
            PublicScoreCacheRepository.BgmScore score = persisted.get();
            long remaining = score.expiresAt() - now;
            if (remaining <= 0) {
                return null;
            }
            CacheUtils.put(cacheKey, score.score(), remaining);
            return score.score();
        } catch (RuntimeException e) {
            log.debug("Unable to read durable Bangumi score cache");
            return null;
        }
    }

    private void persistMikanMapping(String mikanId, String bgmId, long ttlMillis) {
        if (persistentCache == null || StrUtil.isBlank(mikanId) || ttlMillis <= 0 || !backgroundWorkAllowed()) {
            return;
        }
        try {
            persistentCache.saveMikanMapping(mikanId, bgmId, System.currentTimeMillis() + ttlMillis);
        } catch (RuntimeException e) {
            log.debug("Unable to save durable Mikan score mapping cache");
        }
    }

    private void persistBgmScore(String bgmId, double score, long ttlMillis) {
        if (persistentCache == null || StrUtil.isBlank(bgmId) || ttlMillis <= 0 || !backgroundWorkAllowed()) {
            return;
        }
        try {
            persistentCache.saveBgmScore(bgmId, score, System.currentTimeMillis() + ttlMillis);
        } catch (RuntimeException e) {
            log.debug("Unable to save durable Bangumi score cache");
        }
    }

    /**
     * Starts one Mikan detail lookup and queues its Bangumi rating as soon as
     * the mapping completes.  The two work queues reserve score capacity, so
     * a long list of detail pages cannot force completed entries to wait for
     * every remaining mapping request.
     */
    private void warmMikanMappingAndScore(String mikanUrl, boolean mappingAlreadyRead) {
        if (StrUtil.isBlank(mikanUrl)) {
            return;
        }
        String cacheKey = MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl);
        String cached = mappingAlreadyRead
                ? CacheUtils.get(cacheKey)
                : cachedMikanMapping(extractMikanId(mikanUrl), mikanUrl);
        if (cached != null) {
            String bgmId = extractBgmSubjectId(cached);
            if (StrUtil.isNotBlank(bgmId)) {
                warmBgmScore(bgmId);
            }
            return;
        }

        String flightKey = MIKAN_BGM_CACHE_PREFIX + "flight:" + SecureUtil.sha256(mikanUrl);
        startWarmupSingleFlight(
                flightKey,
                () -> loadAndCacheMikanMapping(mikanUrl, mikanBgmIdResolver::load),
                mikanMappingFlights,
                mappingWarmupExecutor
        ).thenAccept(value -> {
            String bgmId = extractBgmSubjectId(value);
            if (StrUtil.isNotBlank(bgmId)) {
                warmBgmScore(bgmId);
            }
        });
    }

    private void warmBgmScore(String bgmId) {
        if (StrUtil.isBlank(bgmId) || cachedBgmScore(bgmId) != null) {
            return;
        }
        String flightKey = BGM_SCORE_CACHE_PREFIX + "flight:" + bgmId;
        startWarmupSingleFlight(
                flightKey,
                () -> loadAndCacheBgmScore(bgmId, subjectId -> Optional.ofNullable(bgmInfoLoader.load(subjectId))
                        .map(BgmInfo::getRating)
                        .map(BgmInfo.Rating::getScore)
                        .filter(score -> score > 0)
                        .orElse(0.0)),
                bgmScoreFlights,
                scoreWarmupExecutor
        );
    }

    private <V> CompletableFuture<V> startWarmupSingleFlight(
            String flightKey,
            Callable<V> loader,
            ConcurrentMap<String, CompletableFuture<V>> flights,
            ExecutorService executor
    ) {
        if (!backgroundWorkAllowed()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "public score warmup is disabled during maintenance"));
        }
        CompletableFuture<V> existing = flights.get(flightKey);
        if (existing != null) {
            return existing;
        }
        if (!isWarmupRetryAllowed(flightKey)) {
            return CompletableFuture.failedFuture(new TimeoutException("public score lookup is cooling down"));
        }

        CompletableFuture<V> created = new CompletableFuture<>();
        CompletableFuture<V> shared = flights.putIfAbsent(flightKey, created);
        if (shared != null) {
            return shared;
        }
        try {
            executor.execute(() -> {
                try {
                    if (!backgroundWorkAllowed()) {
                        throw new IllegalStateException("public score warmup is disabled during maintenance");
                    }
                    V value = callUpstream(loader, deadlineAfter(WARMUP_QUEUE_TIMEOUT_MILLIS));
                    if (!backgroundWorkAllowed()) {
                        throw new IllegalStateException("public score warmup is disabled during maintenance");
                    }
                    warmupRetryAfterNanos.remove(flightKey);
                    created.complete(value);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    warmupRetryAfterNanos.put(flightKey, warmupRetryAfter());
                    created.completeExceptionally(e);
                } catch (Exception e) {
                    warmupRetryAfterNanos.put(flightKey, warmupRetryAfter());
                    created.completeExceptionally(e);
                } finally {
                    flights.remove(flightKey, created);
                }
            });
        } catch (RuntimeException e) {
            warmupRetryAfterNanos.put(flightKey, warmupRetryAfter());
            created.completeExceptionally(e);
            flights.remove(flightKey, created);
        }
        return created;
    }

    private boolean isWarmupRetryAllowed(String flightKey) {
        Long retryAfter = warmupRetryAfterNanos.get(flightKey);
        if (retryAfter == null) {
            return true;
        }
        if (retryAfter <= System.nanoTime()) {
            warmupRetryAfterNanos.remove(flightKey, retryAfter);
            return true;
        }
        return false;
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

    private static long warmupRetryAfter() {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARMUP_FAILURE_RETRY_DELAY_MILLIS);
    }

    private static ExecutorService newWarmupExecutor(int threads, String namePrefix) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, namePrefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    @PreDestroy
    void stopWarmupExecutors() {
        mappingWarmupExecutor.shutdownNow();
        scoreWarmupExecutor.shutdownNow();
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

    /**
     * Extracts the authoritative Bangumi link from a Mikan detail page.
     *
     * <p>Episode tables can make these pages hundreds of KiB, while the
     * canonical subject link is normally emitted in the page header. Reading
     * only the leading document avoids holding a mapper worker until every
     * episode row has arrived. A page that puts the link later still falls
     * back to parsing the complete response, preserving the old behaviour.</p>
     */
    static String loadMikanBgmId(String mikanUrl) throws Exception {
        try (HttpResponse response = HttpReq.get(mikanUrl)
                .timeout(REQUEST_TIMEOUT_MILLIS)
                .executeAsync();
             InputStream input = response.bodyStream()) {
            HttpReq.assertStatus(response);
            ByteArrayOutputStream document = new ByteArrayOutputStream(MIKAN_MAPPING_EARLY_SCAN_BYTES);
            byte[] buffer = new byte[MIKAN_MAPPING_SCAN_BUFFER_BYTES];
            while (document.size() < MIKAN_MAPPING_EARLY_SCAN_BYTES) {
                int limit = Math.min(buffer.length, MIKAN_MAPPING_EARLY_SCAN_BYTES - document.size());
                int read = input.read(buffer, 0, limit);
                if (read < 0) {
                    break;
                }
                document.write(buffer, 0, read);
                // The link is ASCII and normally in the header. Avoid a full
                // Jsoup DOM build for every 4 KiB chunk across a cold season.
                String bgmId = extractLeadingMikanBgmId(document.toString(StandardCharsets.UTF_8));
                if (StrUtil.isNotBlank(bgmId)) {
                    return bgmId;
                }
            }
            input.transferTo(document);
            return extractMikanBgmId(document.toString(StandardCharsets.UTF_8), mikanUrl);
        }
    }

    private static String extractMikanBgmId(String html, String mikanUrl) {
        Document document = Jsoup.parse(html, mikanUrl);
        for (Element link : document.select("a[href]")) {
            String subjectId = extractBgmSubjectId(link.absUrl("href"));
            if (StrUtil.isNotBlank(subjectId)) {
                return subjectId;
            }
        }
        return extractBgmSubjectId(document.html());
    }

    private static String extractLeadingMikanBgmId(String html) {
        Matcher matcher = BGM_SUBJECT_ID.matcher(html);
        return matcher.find() ? matcher.group(1) : "";
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
