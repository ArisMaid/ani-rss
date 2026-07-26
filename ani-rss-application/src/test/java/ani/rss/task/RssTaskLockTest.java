package ani.rss.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RssTaskLockTest {
    @AfterEach
    void releaseLock() {
        RssTask.releaseDownloadLock();
    }

    @Test
    void rejectsSecondRefreshBeforeWorkerStarts() {
        RssTask.acquireDownloadLock();
        assertThrows(IllegalStateException.class, RssTask::acquireDownloadLock);
    }
}
