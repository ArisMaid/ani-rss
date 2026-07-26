package ani.rss.service;

import ani.rss.commons.CacheUtils;
import ani.rss.commons.ProjectMetadata;
import ani.rss.entity.About;
import ani.rss.util.basic.HttpReq;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;

class UpdateServiceVersionTest {
    @Test
    void comparesAgainstTheUpstreamBaseOfALocalForkVersion() {
        assertEquals("3.1.75", UpdateService.upstreamBaseVersion("v3.1.75.12"));
        assertTrue(UpdateService.isUpstreamUpdateAvailable("3.1.76", "3.1.75.12"));
        assertFalse(UpdateService.isUpstreamUpdateAvailable("3.1.75", "3.1.75.12"));
        assertFalse(UpdateService.isUpstreamUpdateAvailable("3.1.74", "3.1.75.12"));
    }

    @Test
    void localForkBuildsCannotBeAutomaticallyReplaced() {
        assertTrue(UpdateService.isLocalForkVersion("3.1.75.12"));
        assertFalse(UpdateService.isLocalForkVersion("3.1.75"));
        assertFalse(UpdateService.isAutomaticUpdateAllowed("3.1.76", "3.1.75.12"));
        assertTrue(UpdateService.isAutomaticUpdateAllowed("3.1.76", "3.1.75"));
        assertFalse(UpdateService.isAutomaticUpdateAllowed("3.2.0", "3.1.75"));
    }

    @Test
    void offlineReleaseCheckFallsBackToLocalMetadataButCannotStartAnUpdate() {
        CacheUtils.remove("github#releases-latest");
        try (MockedStatic<HttpReq> http = mockStatic(HttpReq.class)) {
            http.when(() -> HttpReq.get(ProjectMetadata.LATEST_RELEASE_API))
                    .thenThrow(new IllegalStateException("offline"));

            About about = new UpdateService().about();

            assertFalse(about.getUpdate());
            assertFalse(about.getAutoUpdate());
            assertEquals("", about.getLatest());
            assertThrows(IllegalStateException.class, () -> new UpdateService().update(about));
        } finally {
            CacheUtils.remove("github#releases-latest");
        }
    }
}
