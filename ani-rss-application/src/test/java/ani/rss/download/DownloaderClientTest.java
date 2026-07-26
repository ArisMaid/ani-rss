package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloaderClientTest {
    @Test
    void ownsDefensiveConfigurationSnapshot() {
        Config candidate = new Config().setDownloadToolHost("http://first");
        DownloaderClient client = new DownloaderClient(new StubDownload(true), candidate);
        candidate.setDownloadToolHost("http://changed");

        assertEquals("http://first", client.configurationSnapshot().getDownloadToolHost());
        Config exposed = client.configurationSnapshot().setDownloadToolHost("http://mutated");
        assertEquals("http://mutated", exposed.getDownloadToolHost());
        assertEquals("http://first", client.configurationSnapshot().getDownloadToolHost());
    }

    @Test
    void returnsStableTypedFailures() {
        DownloaderClient rejected = new DownloaderClient(new StubDownload(false), new Config());
        assertEquals("CONNECTION_REJECTED", rejected.connect(true).errorCode());
        assertFalse(rejected.connect(true).retryable());

        DownloaderClient timedOut = new DownloaderClient(new TimeoutDownload(), new Config());
        DownloaderResult<Void> result = timedOut.connect(true);
        assertFalse(result.isSuccess());
        assertTrue(result.retryable());
        assertEquals("DOWNLOADER_UNCHECKEDIOEXCEPTION", result.errorCode());
    }

    private static class StubDownload implements BaseDownload {
        private final boolean result;

        private StubDownload(boolean result) {
            this.result = result;
        }

        @Override public Boolean login(Boolean test, Config config) { return result; }
        @Override public List<TorrentsInfo> getTorrentsInfos() { return List.of(); }
        @Override public Boolean download(Ani ani, Item item, String savePath, File torrentFile) { return result; }
        @Override public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) { return result; }
        @Override public Boolean rename(TorrentsInfo torrentsInfo) { return result; }
        @Override public Boolean addTags(TorrentsInfo torrentsInfo, String tags) { return result; }
        @Override public void updateTrackers(Set<String> trackers) { }
        @Override public void setSavePath(TorrentsInfo torrentsInfo, String path) { }
    }

    private static final class TimeoutDownload extends StubDownload {
        private TimeoutDownload() { super(false); }

        @Override
        public Boolean login(Boolean test, Config config) {
            throw new UncheckedIOException(new SocketTimeoutException("timeout"));
        }
    }
}
