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
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
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
    static final long BGM_BATCH_TIMEOUT_MILLIS = 12_000;
    static final long MIKAN_MAPPING_BATCH_TIMEOUT_MILLIS = 12_000;
    static final int MAX_CONCURRENT_REQUESTS = 12;
    /** Keep two upstream slots available so completed Mikan mappings can turn into scores immediately. */
    private static final int MAX_MAPPING_WARMUP_WORKERS = MAX_CONCURRENT_REQUESTS - 2;
    private static final int MAX_SCORE_WARMUP_WORKERS = 2;
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

        for (String subjectId : ids) {
            Double cached = cachedBgmScore(subjectId);
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
        Map<String, MikanBgm> scores = new LinkedHashMap<>();
        Set<String> retryableMikanIds = new LinkedHashSet<>();
        if (mikanInfos == null) {
            return new MikanScoreLookup(scores, retryableMikanIds);
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
            if (StrUtil.isBlank(bgmId)) {
                String mikanUrl = mikanInfo.getUrl();
                String cachedMapping = cachedMikanMapping(mikanId, mikanUrl);
                if (cachedMapping == null) {
                    retryableMikanIds.add(mikanId);
                    warmMikanMappingAndScore(mikanUrl);
                    continue;
                }
                bgmId = extractBgmSubjectId(cachedMapping);
                if (StrUtil.isBlank(bgmId)) {
                    // A completed lookup with no Bangumi link is cached briefly
                    // and is not an upstream outage worth polling again.
                    continue;
                }
            }

            Double cachedScore = cachedBgmScore(bgmId);
            if (cachedScore != null) {
                scores.put(mikanId, new MikanBgm(mikanId, bgmId, cachedScore));
                continue;
            }
            retryableMikanIds.add(mikanId);
            warmBgmScore(bgmId);
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
                bgmId = cachedMikanMapping(mikanId, mikanInfo.getUrl());
            }
            if (StrUtil.isBlank(bgmId)) {
                continue;
            }

            Double score = cachedBgmScore(bgmId);
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
            String cached = cachedMikanMapping(mikanId, url);
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
    private void warmMikanMappingAndScore(String mikanUrl) {
        if (StrUtil.isBlank(mikanUrl)) {
            return;
        }
        String cached = cachedMikanMapping(extractMikanId(mikanUrl), mikanUrl);
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
