package ani.rss.util.other;

import ani.rss.download.DownloaderClient;
import ani.rss.entity.torrent.TorrentsInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class TorrentSnapshotCycleTest {

    @Test
    void usesTheInjectedClockForExactFiveSecondExpiryAndDirtyReads() {
        AtomicLong now = new AtomicLong();
        TorrentUtil.SnapshotCycle cycle = new TorrentUtil.SnapshotCycle(null, now::get);
        DownloaderClient client = org.mockito.Mockito.mock(DownloaderClient.class);
        List<TorrentsInfo> tasks = List.of(new TorrentsInfo().setId("task-1"));

        cycle.store(client, tasks);
        assertSame(tasks, cycle.cached(client));

        now.set(4_999_000_000L);
        assertSame(tasks, cycle.cached(client));

        now.set(5_000_000_000L);
        assertNull(cycle.cached(client));

        cycle.store(client, tasks);
        cycle.markDirty();
        assertNull(cycle.cached(client));
    }
}
