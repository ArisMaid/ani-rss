package ani.rss.service;

import ani.rss.entity.BgmInfo;
import ani.rss.entity.MikanBgm;
import ani.rss.entity.MikanInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicScoreServiceTest {
    private static final AtomicLong IDS = new AtomicLong(8_000_000_000L);

    @Test
    void resolvesPublicScoresWithoutAnyDonationOrOrderState() {
        String subjectId = uniqueNumericId();
        AtomicInteger requests = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    requests.incrementAndGet();
                    return rated(id, 8.6);
                },
                url -> ""
        );

        Map<String, Double> first = service.getBgmScores(List.of(subjectId));
        Map<String, Double> second = service.getBgmScores(List.of(subjectId));

        assertEquals(8.6, first.get(subjectId));
        assertEquals(8.6, second.get(subjectId));
        assertEquals(1, requests.get(), "a public result should be cached locally");
    }

    @Test
    void resolvesEachMikanEntryIndependentlyForDifferentSeasons() {
        String mikanSpring = uniqueNumericId();
        String mikanSummer = uniqueNumericId();
        String bgmSpring = uniqueNumericId();
        String bgmSummer = uniqueNumericId();

        PublicScoreService service = new PublicScoreService(
                id -> rated(id, id.equals(bgmSpring) ? 7.2 : 8.9),
                url -> url.endsWith(mikanSpring) ? bgmSpring : bgmSummer
        );

        Map<String, MikanBgm> scores = service.getMikanScores(List.of(
                new MikanInfo().setUrl("https://mikan.example/Home/Bangumi/" + mikanSpring),
                new MikanInfo().setUrl("https://mikan.example/Home/Bangumi/" + mikanSummer)
        ));

        assertEquals(bgmSpring, scores.get(mikanSpring).getBgmId());
        assertEquals(7.2, scores.get(mikanSpring).getScore());
        assertEquals(bgmSummer, scores.get(mikanSummer).getBgmId());
        assertEquals(8.9, scores.get(mikanSummer).getScore());
    }

    @Test
    void upstreamFailuresDegradeToZeroInsteadOfFailingTheList() {
        String subjectId = uniqueNumericId();
        AtomicInteger requests = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    requests.incrementAndGet();
                    throw new IllegalStateException("upstream unavailable");
                },
                url -> ""
        );

        Map<String, Double> scores = service.getBgmScores(List.of(subjectId));
        Map<String, Double> second = service.getBgmScores(List.of(subjectId));

        assertEquals(0.0, scores.get(subjectId));
        assertEquals(0.0, second.get(subjectId));
        assertFalse(scores.isEmpty());
        assertEquals(1, requests.get(), "failed public lookups should be short-lived cached too");
    }

    @Test
    void extractsOnlyTrustedMikanAndBangumiIdentifiers() {
        assertEquals("3901", PublicScoreService.extractMikanId("https://mikanani.me/Home/Bangumi/3901"));
        assertEquals("123", PublicScoreService.extractBgmSubjectId("https://bgm.tv/subject/123"));
        assertEquals("", PublicScoreService.extractMikanId("https://mikanani.me/Home/Other/3901"));
        assertEquals("", PublicScoreService.extractBgmSubjectId("https://example.com/subject/123"));
        assertTrue(PublicScoreService.extractBgmSubjectId("123").equals("123"));
    }

    @Test
    void capsAColdBatchWithoutCachingSkippedSubjectsAsFailures() {
        AtomicInteger requests = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> {
                    requests.incrementAndGet();
                    return rated(id, 7.8);
                },
                url -> ""
        );
        List<String> subjectIds = IntStream.range(0, PublicScoreService.MAX_SCORE_LOOKUPS_PER_BATCH + 6)
                .mapToObj(index -> uniqueNumericId())
                .toList();

        Map<String, Double> first = service.getBgmScores(subjectIds);

        assertEquals(PublicScoreService.MAX_SCORE_LOOKUPS_PER_BATCH, requests.get());
        assertEquals(0.0, first.get(subjectIds.get(subjectIds.size() - 1)));

        Map<String, Double> second = service.getBgmScores(subjectIds);

        assertEquals(subjectIds.size(), requests.get(), "subjects skipped by the request budget must be retried later");
        assertEquals(7.8, second.get(subjectIds.get(subjectIds.size() - 1)));
    }

    @Test
    void capsColdMikanDetailLookupsBeforeTheyCanDelayThePicker() {
        AtomicInteger detailRequests = new AtomicInteger();
        PublicScoreService service = new PublicScoreService(
                id -> rated(id, 8.1),
                url -> {
                    detailRequests.incrementAndGet();
                    return uniqueNumericId();
                }
        );
        List<MikanInfo> entries = IntStream.range(0, PublicScoreService.MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH + 5)
                .mapToObj(index -> new MikanInfo().setUrl(
                        "https://mikanani.me/Home/Bangumi/" + uniqueNumericId()
                ))
                .toList();

        Map<String, MikanBgm> scores = service.getMikanScores(entries);

        assertEquals(PublicScoreService.MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH, detailRequests.get());
        assertEquals(PublicScoreService.MAX_MIKAN_MAPPING_LOOKUPS_PER_BATCH, scores.size());
    }

    private static BgmInfo rated(String id, double score) {
        return new BgmInfo()
                .setId(id)
                .setRating(new BgmInfo.Rating().setScore(score));
    }

    private static String uniqueNumericId() {
        return String.valueOf(IDS.incrementAndGet());
    }
}
