package ani.rss.download;

import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.ConfigUtil;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
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
        return booleanOperation(() -> adapter.login(test, configuration), "CONNECTION_REJECTED");
    }

    public DownloaderResult<List<TorrentsInfo>> torrents() {
        return operation(() -> DownloaderResult.success(adapter.getTorrentsInfos()));
    }

    public DownloaderResult<Void> download(Ani ani, Item item, String savePath, File torrentFile) {
        return operation(() -> {
            if (!Boolean.TRUE.equals(adapter.download(ani, item, savePath, torrentFile))) {
                return DownloaderResult.rejected("DOWNLOAD_REJECTED");
            }
            String remoteTaskId = null;
            try {
                remoteTaskId = adapter.getTorrentsInfos().stream()
                        .filter(task -> (item.getInfoHash() != null &&
                                item.getInfoHash().equalsIgnoreCase(task.getHash())) ||
                                (item.getReName() != null && item.getReName().equals(task.getName())))
                        .map(TorrentsInfo::getId)
                        .findFirst()
                        .orElse(null);
            } catch (RuntimeException ignored) {
                // The task was accepted; ownership remains PENDING until a later observation.
            }
            return DownloaderResult.success(null, remoteTaskId);
        });
    }

    public DownloaderResult<Void> delete(TorrentsInfo torrent, boolean deleteFiles) {
        return booleanOperation(() -> adapter.delete(torrent, deleteFiles), "DELETE_REJECTED");
    }

    public DownloaderResult<Void> rename(TorrentsInfo torrent) {
        return booleanOperation(() -> adapter.rename(torrent), "RENAME_REJECTED");
    }

    public DownloaderResult<Void> addTags(TorrentsInfo torrent, String tags) {
        return booleanOperation(() -> adapter.addTags(torrent, tags), "TAG_REJECTED");
    }

    public DownloaderResult<Void> updateTrackers(Set<String> trackers) {
        return operation(() -> {
            adapter.updateTrackers(trackers);
            return DownloaderResult.success(null);
        });
    }

    public DownloaderResult<Void> setSavePath(TorrentsInfo torrent, String path) {
        return operation(() -> {
            adapter.setSavePath(torrent, path);
            return DownloaderResult.success(null);
        });
    }

    public Config configurationSnapshot() {
        return ConfigUtil.copy(configuration);
    }

    public BaseDownload adapter() {
        return adapter;
    }

    private DownloaderResult<Void> booleanOperation(Callable<Boolean> action, String rejectedCode) {
        return operation(() -> Boolean.TRUE.equals(action.call())
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected(rejectedCode));
    }

    private static <T> DownloaderResult<T> operation(Callable<DownloaderResult<T>> action) {
        try {
            return action.call();
        } catch (Exception e) {
            return DownloaderResult.failed("DOWNLOADER_" + e.getClass().getSimpleName().toUpperCase(), retryable(e));
        }
    }

    private static boolean retryable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException ||
                    current instanceof IOException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
