package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.ConfigUtil;

import java.io.File;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

/** A downloader adapter bound to one defensive configuration snapshot. */
public final class DownloaderClient {
    private final BaseDownload adapter;
    private final Config configuration;

    DownloaderClient(BaseDownload adapter, Config configuration) {
        this.adapter = adapter;
        this.configuration = ConfigUtil.copy(configuration);
    }

    public DownloaderResult<Void> connect(boolean test) {
        return operation(() -> adapter.connectResult(test, configuration));
    }

    public DownloaderResult<List<TorrentsInfo>> torrents() {
        return operation(adapter::torrentsResult);
    }

    public DownloaderResult<Void> download(Ani ani, Item item, String savePath, File torrentFile) {
        return operation(() -> adapter.downloadResult(ani, item, savePath, torrentFile));
    }

    public DownloaderResult<Void> delete(TorrentsInfo torrent, boolean deleteFiles) {
        return operation(() -> adapter.deleteResult(torrent, deleteFiles));
    }

    public DownloaderResult<Void> rename(TorrentsInfo torrent) {
        return operation(() -> adapter.renameResult(torrent));
    }

    public DownloaderResult<Void> addTags(TorrentsInfo torrent, String tags) {
        return operation(() -> adapter.addTagsResult(torrent, tags));
    }

    public DownloaderResult<Void> updateTrackers(Set<String> trackers) {
        return operation(() -> adapter.updateTrackersResult(trackers));
    }

    public DownloaderResult<Void> setSavePath(TorrentsInfo torrent, String path) {
        return operation(() -> adapter.setSavePathResult(torrent, path));
    }

    public DownloaderResult<Void> recover(TorrentsInfo torrent) {
        return operation(() -> adapter.recoverResult(torrent));
    }

    public Config configurationSnapshot() {
        return ConfigUtil.copy(configuration);
    }

    public BaseDownload adapter() {
        return adapter;
    }

    private static <T> DownloaderResult<T> operation(Callable<DownloaderResult<T>> action) {
        try {
            DownloaderResult<T> result = action.call();
            return result == null
                    ? DownloaderResult.failed("DOWNLOADER_INVALID_RESULT", false)
                    : result;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return DownloaderFailures.result(e);
        }
    }
}
