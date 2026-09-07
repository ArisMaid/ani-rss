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
import java.util.concurrent.ArrayBlockingQueue;
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
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
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
    static final int MAX_CONCURRENT_REQUESTS = 4;
    private static final int MAX_SCORE_WARMUP_WORKERS = 4;
    private static final long WARMUP_QUEUE_TIMEOUT_MILLIS = 12_000;
    private static final long WARMUP_FAILURE_RETRY_DELAY_MILLIS = 30_000;
    /** Durable score cache writes are optional and must never occupy a mapping worker. */
    private static final int PERSISTENCE_QUEUE_CAPACITY = 256;
    static final int MAX_SCORE_LOOKUPS_PER_BATCH = 64;
    static final int MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH = 48;
    private static final long SCORE_CACHE_TTL = TimeUnit.HOURS.toMillis(6);
    private static final long NEGATIVE_CACHE_TTL = TimeUnit.MINUTES.toMillis(10);
    private static final long PERSISTENT_MAPPING_CACHE_TTL = TimeUnit.DAYS.toMillis(14);
    private static final long PERSISTENT_SCORE_CACHE_TTL = SCORE_CACHE_TTL;
    private static final String BGM_SCORE_CACHE_PREFIX = "public-score:bgm:";
    /** Internal negative-cache marker; never crosses an API response boundary. */
    private static final Object NO_SCORE_MARKER = new Object();
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
     * Mapping and score work share one bounded budget. A mapping completion
     * queues its score lookup without waiting on the same pool, so cold lists
     * cannot create an unbounded second queue.
     */
    private final ExecutorService warmupExecutor = newWarmupExecutor(
            MAX_SCORE_WARMUP_WORKERS, "public-score");
    /**
     * SQLite uses a single serialized connection.  Keep cache persistence off
     * the latency-critical mapping and score workers, otherwise every newly
     * resolved card waits behind an individual durable-cache upsert before it
     * can expose its score to the picker.
     */
    private final ExecutorService cachePersistenceExecutor = newPersistenceExecutor();
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
     * Returns completed scores for valid requested subject ids. A failed or
     * not-yet-rated subject is omitted and never represented as a successful
     * zero-valued score.
     */
    public Map<String, Double> getBgmScores(Collection<String> subjectIds) {
        return getBgmScoreLookup(subjectIds, deadlineAfter(BGM_BATCH_TIMEOUT_MILLIS)).scores();
    }

    /**
     * Reads only already-computed Bangumi scores and schedules missing ids in
     * the bounded warmup pool.  Optional enrichment callers must use this
     * entry point so their list response never waits on the score upstream.
     */
    public BgmScoreLookup getCachedBgmScoresAndWarm(Collection<String> subjectIds) {
        LinkedHashSet<String> ids = normalizedIds(subjectIds);
        Map<String, Double> cached = cachedBgmScores(ids);
        Map<String, Double> scores = new LinkedHashMap<>();
        Set<String> retryable = new LinkedHashSet<>();
        for (String subjectId : ids) {
            if (cached.containsKey(subjectId)) {
                if (isVisibleScore(cached.get(subjectId))) {
                    scores.put(subjectId, cached.get(subjectId));
                }
                continue;
            }
            retryable.add(subjectId);
            warmBgmScore(subjectId, true);
        }
        return new BgmScoreLookup(scores, retryable);
    }

    private BgmScoreLookup getBgmScoreLookup(Collection<String> subjectIds, long deadlineNanos) {
        LinkedHashSet<String> ids = normalizedIds(subjectIds);
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, String> missing = new LinkedHashMap<>();
        Set<String> retryableBgmIds = new LinkedHashSet<>();
        Map<String, Double> cachedScores = cachedBgmScores(ids);

        for (String subjectId : ids) {
            if (cachedScores.containsKey(subjectId)) {
                Double score = cachedScores.get(subjectId);
                if (isVisibleScore(score)) {
                    scores.put(subjectId, score);
                }
            } else {
                missing.put(subjectId, subjectId);
            }
        }

        Map<String, String> attempted = takeFirst(missing, MAX_SCORE_LOOKUPS_PER_BATCH);
        Set<String> completed = new LinkedHashSet<>();
        for (LookupResult<String, Double> result : resolveScoreBounded(attempted, subjectId -> {
            BgmInfo info = bgmInfoLoader.load(subjectId);
            return publicScore(info);
        }, deadlineNanos)) {
            completed.add(result.key());
            if (!result.completed()) {
                retryableBgmIds.add(result.key());
                continue;
            }
            Double score = result.value();
            if (!isVisibleScore(score)) {
                // A successful response with no usable rating is terminal for
                // the short negative-cache window, but it is not a score of 0.
                CacheUtils.put(
                        BGM_SCORE_CACHE_PREFIX + result.key(),
                        NO_SCORE_MARKER,
                        NEGATIVE_CACHE_TTL
                );
                continue;
            }
            CacheUtils.put(
                    BGM_SCORE_CACHE_PREFIX + result.key(),
                    score,
                    SCORE_CACHE_TTL
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
                // cachedBgmScores already checked both memory and SQLite for
                // this complete batch. Rechecking the same durable key once
                // per card would serialize a cold season behind the SQLite
                // connection before any score worker can start.
                warmBgmScore(bgmId, true);
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
            Double score = scores.get(bgmId);
            if (score != null) {
                result.put(mikanId, new MikanBgm(mikanId, bgmId, score));
            }
            if (scoreLookup.retryableSubjectIds().contains(bgmId)) {
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
        long observedAt = System.currentTimeMillis();
        long ttlMillis = StrUtil.isNotBlank(bgmId) ? PERSISTENT_MAPPING_CACHE_TTL : NEGATIVE_CACHE_TTL;
        CacheUtils.put(
                MIKAN_BGM_CACHE_PREFIX + SecureUtil.sha256(mikanUrl),
                bgmId,
                Math.max(1, observedAt + ttlMillis - System.currentTimeMillis())
        );
        // Carry the absolute expiry across the asynchronous writer.  A busy
        // SQLite writer must not renew a mapping's 14-day lifetime when it
        // eventually obtains the connection.
        persistMikanMapping(extractMikanId(mikanUrl), bgmId, observedAt + ttlMillis);
        return value;
    }

    private Double loadAndCacheBgmScore(String subjectId, ScoreLoader loader) throws Exception {
        Double score = loader.load(subjectId);
        if (!isVisibleScore(score)) {
            score = null;
        }
        long observedAt = System.currentTimeMillis();
        long ttlMillis = score == null ? NEGATIVE_CACHE_TTL : PERSISTENT_SCORE_CACHE_TTL;
        long expiresAt = observedAt + ttlMillis;
        long memoryTtl = expiresAt - System.currentTimeMillis();
        CacheUtils.put(
                BGM_SCORE_CACHE_PREFIX + subjectId,
                score == null ? NO_SCORE_MARKER : score,
                Math.max(1, memoryTtl)
        );
        if (score != null && isVisibleScore(score)) {
            persistBgmScore(subjectId, score, expiresAt, observedAt);
        }
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
            Object cached = CacheUtils.get(BGM_SCORE_CACHE_PREFIX + bgmId);
            if (cached instanceof Number number && Double.isFinite(number.doubleValue())
                    && number.doubleValue() >= 0) {
                result.put(bgmId, number.doubleValue());
            } else if (cached == NO_SCORE_MARKER) {
                // Keep the key present internally so a terminal no-score
                // response is not mistaken for a cold lookup.  It is removed
                // from public response maps by the caller.
                result.put(bgmId, Double.NaN);
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
                long remaining = persistentScoreRemaining(score, now);
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
        Object cached = CacheUtils.get(cacheKey);
        if (cached == NO_SCORE_MARKER) {
            return Double.NaN;
        }
        if (cached instanceof Number number) {
            double value = number.doubleValue();
            return isVisibleScore(value) ? value : null;
        }
        if (persistentCache == null || !backgroundWorkAllowed()) {
            return null;
        }
        try {
            long now = System.currentTimeMillis();
            Optional<PublicScoreCacheRepository.BgmScore> persisted = persistentCache.findBgmScore(bgmId, now);
            if (persisted.isEmpty()) {
                return null;
            }
            PublicScoreCacheRepository.BgmScore score = persisted.get();
            long remaining = persistentScoreRemaining(score, now);
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

    private void persistMikanMapping(String mikanId, String bgmId, long expiresAt) {
        if (persistentCache == null || StrUtil.isBlank(mikanId)
                || expiresAt <= System.currentTimeMillis() || !backgroundWorkAllowed()) {
            return;
        }
        scheduleCachePersistence("Mikan score mapping", () ->
                persistentCache.saveMikanMapping(mikanId, bgmId, expiresAt));
    }

    private void persistBgmScore(String bgmId, double score, long expiresAt, long observedAt) {
        if (persistentCache == null || StrUtil.isBlank(bgmId) || expiresAt <= observedAt
                || !backgroundWorkAllowed()) {
            return;
        }
        scheduleCachePersistence("Bangumi score", () ->
                persistentCache.saveBgmScore(bgmId, score, expiresAt, observedAt));
    }

    private static long persistentScoreRemaining(
            PublicScoreCacheRepository.BgmScore score, long now) {
        long remaining = score.expiresAt() - now;
        if (score.updatedAt() <= 0) return -1;
        return Math.min(remaining, score.updatedAt() + SCORE_CACHE_TTL - now);
    }

    private void scheduleCachePersistence(String description, Runnable operation) {
        try {
            cachePersistenceExecutor.execute(() -> {
                // A restore or maintenance operation must not race a cache write.
                if (!backgroundWorkAllowed()) {
                    return;
                }
                try {
                    operation.run();
                } catch (RuntimeException e) {
                    // The in-memory cache already contains the result. Losing
                    // an optional durable acceleration must not affect the
                    // currently visible score.
                    log.debug("Unable to save durable {} cache", description);
                }
            });
        } catch (RejectedExecutionException e) {
            // The bounded queue is deliberately allowed to drop optional cache
            // work under a pathological burst instead of slowing the picker.
            log.debug("Durable {} cache write queue is full", description);
        }
    }

    /**
     * Starts one Mikan detail lookup and queues its Bangumi rating as soon as
     * the mapping completes. Mapping and rating work share one bounded pool;
     * a long list cannot create an unbounded second queue.
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
                warmupExecutor
        ).thenAccept(value -> {
            String bgmId = extractBgmSubjectId(value);
            if (StrUtil.isNotBlank(bgmId)) {
                warmBgmScore(bgmId);
            }
        }).exceptionally(error -> {
            log.debug("Mikan public score warmup failed: {}", error.getClass().getSimpleName());
            return null;
        });
    }

    private void warmBgmScore(String bgmId) {
        warmBgmScore(bgmId, false);
    }

    /**
     * Queues a missing score without repeating a durable-cache lookup when a
     * preceding batch read already established that this exact id is cold.
     */
    private void warmBgmScore(String bgmId, boolean scoreAlreadyRead) {
        if (StrUtil.isBlank(bgmId)) {
            return;
        }
        Object cached = scoreAlreadyRead
                ? CacheUtils.get(BGM_SCORE_CACHE_PREFIX + bgmId)
                : cachedBgmScore(bgmId);
        if (cached == NO_SCORE_MARKER
                || cached instanceof Number number
                && (Double.isNaN(number.doubleValue()) || isVisibleScore(number.doubleValue()))) {
            return;
        }
        String flightKey = BGM_SCORE_CACHE_PREFIX + "flight:" + bgmId;
        startWarmupSingleFlight(
                flightKey,
                () -> loadAndCacheBgmScore(bgmId, subjectId -> publicScore(bgmInfoLoader.load(subjectId))),
                bgmScoreFlights,
                warmupExecutor
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
        // Start the deadline before enqueueing.  A task waiting behind the
        // bounded queue must not receive a fresh timeout after it finally
        // reaches a worker.
        long deadlineNanos = deadlineAfter(WARMUP_QUEUE_TIMEOUT_MILLIS);
        try {
            executor.execute(() -> {
                try {
                    if (!backgroundWorkAllowed()) {
                        throw new IllegalStateException("public score warmup is disabled during maintenance");
                    }
                    V value = callUpstream(loader, deadlineNanos);
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
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(96),
                newDaemonThreadFactory(namePrefix),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static ExecutorService newPersistenceExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(PERSISTENCE_QUEUE_CAPACITY),
                newDaemonThreadFactory("public-score-cache"),
                new ThreadPoolExecutor.AbortPolicy()
        );
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
    void stopWarmupExecutors() {
        warmupExecutor.shutdownNow();
        cachePersistenceExecutor.shutdownNow();
    }

    private <K, V> List<LookupResult<K, V>> invokeBounded(
            List<Callable<LookupResult<K, V>>> tasks,
            long deadlineNanos
    ) {
        if (tasks.isEmpty()) {
            return List.of();
        }

        List<Future<LookupResult<K, V>>> futures = new ArrayList<>();
        try {
            for (Callable<LookupResult<K, V>> task : tasks) {
                try {
                    // Keep synchronous compatibility callers on the same
                    // bounded pool as background enrichment. A per-request
                    // executor would bypass the global four-worker budget.
                    futures.add(warmupExecutor.submit(task));
                } catch (RejectedExecutionException e) {
                    // The caller will retain unsubmitted keys as retryable.
                    break;
                }
            }
            List<LookupResult<K, V>> results = new ArrayList<>();
            for (Future<LookupResult<K, V>> future : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    break;
                }
                if (future.isCancelled()) {
                    continue;
                }
                try {
                    results.add(future.get(remainingNanos, TimeUnit.NANOSECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (TimeoutException e) {
                    future.cancel(true);
                    break;
                } catch (Exception ignored) {
                    // A score is optional; one malformed or unreachable subject must not break a list.
                }
            }
            return results;
        } finally {
            for (Future<LookupResult<K, V>> future : futures) {
                if (!future.isDone()) {
                    future.cancel(true);
                }
            }
        }
    }

    private static Double publicScore(BgmInfo info) {
        if (info == null || info.getRating() == null) {
            return null;
        }
        Double score = info.getRating().getScore();
        return isVisibleScore(score) ? score : null;
    }

    private static boolean isVisibleScore(Double score) {
        return score != null && Double.isFinite(score) && score >= 0;
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

    public record BgmScoreLookup(Map<String, Double> scores, Set<String> retryableSubjectIds) {
        public BgmScoreLookup {
            scores = scores == null ? Map.of() : Map.copyOf(scores);
            retryableSubjectIds = retryableSubjectIds == null
                    ? Set.of() : Set.copyOf(retryableSubjectIds);
        }
    }

    public record MikanScoreLookup(Map<String, MikanBgm> scores, Set<String> retryableMikanIds) {
        public MikanScoreLookup {
            scores = Map.copyOf(scores);
            retryableMikanIds = Set.copyOf(retryableMikanIds);
        }
    }

    private record MikanBgmResolution(Map<String, String> bgmIds, Set<String> retryableMikanIds) {
    }

    private record LookupResult<K, V>(K key, V value, boolean completed) {
    }
}
