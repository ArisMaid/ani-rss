package ani.rss.service;

import ani.rss.entity.AnimeGarden;
import ani.rss.entity.BgmInfo;
import ani.rss.exception.ApiProblemException;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimeGardenServiceTest {

    @Test
    void keepsAnEarlierListSnapshotValidWhenAnotherConsumerLoadsAList() {
        AtomicInteger loads = new AtomicInteger();
        AnimeGardenService service = newService(() -> {
            int offset = loads.getAndIncrement() * 2 + 1;
            return List.of(subject(String.valueOf(offset)), subject(String.valueOf(offset + 1)));
        });

        service.list("");
        service.list("");

        AnimeGarden.EnrichmentResponse result = service.enrich(List.of("1", "2"));

        assertEquals(Set.of("1", "2"), result.getSubjects().keySet(),
                "a later list request must not invalidate the first consumer's ids");
        assertThrows(ApiProblemException.class, () -> service.enrich(List.of("999")),
                "an id never returned by a list must be rejected before enrichment");
    }

    @Test
    void keepsAllSubjectsWhenAListContainsMoreThanTheOld512EntryLimit() {
        AnimeGardenService service = newService(() -> IntStream.rangeClosed(1, 513)
                .mapToObj(index -> subject(String.valueOf(index)))
                .toList());

        service.list("");

        assertTrue(service.enrich(List.of("513")).getSubjects().containsKey("513"));
    }

    @Test
    void reportsStableConflictWhenAListSnapshotExpires() {
        AtomicLong now = new AtomicLong(1_000L);
        AnimeGardenService service = new AnimeGardenService(
                () -> List.of(subject("123")),
                emptyCache(),
                new PublicScoreService(id -> null, url -> "") {
                    @Override
                    public BgmScoreLookup getCachedBgmScoresAndWarm(java.util.Collection<String> subjectIds) {
                        return new BgmScoreLookup(Map.of(), Set.copyOf(subjectIds));
                    }
                },
                id -> null,
                now::get);
        service.list("");
        now.addAndGet(10 * 60 * 1000L);

        ApiProblemException error = assertThrows(ApiProblemException.class,
                () -> service.enrich(List.of("123")));

        assertEquals(HttpStatus.CONFLICT, error.status());
        assertEquals("ANIME_GARDEN_LIST_EXPIRED", error.code());
    }

    @Test
    void reportsStableConflictWhenAnOldSnapshotIsEvicted() {
        AtomicInteger nextId = new AtomicInteger();
        AnimeGardenService service = newService(() ->
                List.of(subject(String.valueOf(nextId.incrementAndGet()))));

        for (int index = 0; index < 33; index++) {
            service.list("");
        }

        ApiProblemException error = assertThrows(ApiProblemException.class,
                () -> service.enrich(List.of("1")));

        assertEquals("ANIME_GARDEN_LIST_EXPIRED", error.code());
    }

    @Test
    void ignoresInvalidIdsWithoutCallingTheScoreSource() {
        AtomicInteger scoreRequests = new AtomicInteger();
        PublicScoreService scores = new PublicScoreService(
                id -> {
                    scoreRequests.incrementAndGet();
                    return new BgmInfo().setRating(new BgmInfo.Rating().setScore(8.0));
                },
                url -> ""
        ) {
            @Override
            public BgmScoreLookup getCachedBgmScoresAndWarm(java.util.Collection<String> subjectIds) {
                scoreRequests.addAndGet(subjectIds.size());
                return new BgmScoreLookup(Map.of(), Set.copyOf(subjectIds));
            }
        };
        CacheService cache = emptyCache();
        AnimeGardenService service = new AnimeGardenService(
                () -> List.of(subject("123")), cache, scores);
        service.list("");

        assertThrows(IllegalArgumentException.class,
                () -> service.enrich(List.of("../123", "abc", "999")));

        assertEquals(0, scoreRequests.get());
        scores.stopWarmupExecutors();
    }

    @Test
    void returnsPartialEnrichmentAndRetryableStateForAColdScore() {
        PublicScoreService scores = new PublicScoreService(id -> null, url -> "") {
            @Override
            public BgmScoreLookup getCachedBgmScoresAndWarm(java.util.Collection<String> subjectIds) {
                return new BgmScoreLookup(Map.of(), Set.copyOf(subjectIds));
            }
        };
        AnimeGardenService service = new AnimeGardenService(
                () -> List.of(subject("123")), emptyCache(), scores);
        service.list("");

        AnimeGarden.Enrichment enrichment = service.enrich(List.of("123"))
                .getSubjects().get("123");

        assertEquals(null, enrichment.getScore());
        assertTrue(service.enrich(List.of("123")).getRetryableSubjectIds().contains("123"));
        scores.stopWarmupExecutors();
    }

    private static AnimeGardenService newService(AnimeGardenService.SubjectLoader loader) {
        return new AnimeGardenService(loader, emptyCache(), new PublicScoreService(id -> null, url -> "") {
            @Override
            public BgmScoreLookup getCachedBgmScoresAndWarm(java.util.Collection<String> subjectIds) {
                return new BgmScoreLookup(Map.of(), Set.copyOf(subjectIds));
            }
        });
    }

    private static CacheService emptyCache() {
        return new CacheService() {
            @Override
            public JsonObject getBgmCoverSnapshot() {
                return new JsonObject();
            }

            @Override
            public JsonObject getBgmCoverForEnrichment() {
                return new JsonObject();
            }

            @Override
            public CoverLookup getBgmCoverForEnrichmentSnapshot() {
                return new CoverLookup(new JsonObject(), false, 0, 0);
            }
        };
    }

    private static AnimeGarden.Subject subject(String id) {
        return new AnimeGarden.Subject()
                .setId(id)
                .setName("subject-" + id)
                .setActivedAt(new Date())
                .setScore(null);
    }
}
