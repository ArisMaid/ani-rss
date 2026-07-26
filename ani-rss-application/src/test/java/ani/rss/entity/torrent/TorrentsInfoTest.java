package ani.rss.entity.torrent;

import ani.rss.enums.TorrentsStateEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentsInfoTest {
    @Test
    void completedStateStillRequiresCompleteProgressWhenProgressIsKnown() {
        TorrentsInfo inconsistent = new TorrentsInfo()
                .setState(TorrentsStateEnum.uploading)
                .setProgress(99.99);

        assertFalse(inconsistent.finished());
        assertTrue(inconsistent.setProgress(100D).finished());
    }

    @Test
    void recognizesAllSupportedCompletedStatesWithoutProgress() {
        for (TorrentsStateEnum state : new TorrentsStateEnum[]{
                TorrentsStateEnum.queuedUP,
                TorrentsStateEnum.uploading,
                TorrentsStateEnum.stalledUP,
                TorrentsStateEnum.stoppedUP}) {
            assertTrue(new TorrentsInfo().setState(state).finished(), state.name());
        }
        assertFalse(new TorrentsInfo()
                .setState(TorrentsStateEnum.downloading)
                .setProgress(100D)
                .finished());
    }
}
