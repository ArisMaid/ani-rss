package ani.rss.download;

import ani.rss.entity.Config;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.core.util.StrUtil;

/** Creates isolated downloader instances for active and connection-test operations. */
public final class DownloaderClientFactory {
    private DownloaderClientFactory() {
    }

    public static BaseDownload create(Config candidate) {
        Config snapshot = ConfigUtil.copy(candidate);
        String type = StrUtil.blankToDefault(snapshot.getDownloadToolType(), "qBittorrent");
        return switch (type) {
            case "Transmission" -> new Transmission(snapshot);
            case "Aria2" -> new Aria2(snapshot);
            case "OpenList", "Alist" -> new OpenList(snapshot);
            case "qBittorrent" -> new qBittorrent(downloadService(), snapshot);
            default -> throw new IllegalArgumentException("不支持的下载器: " + type);
        };
    }

    public static DownloaderClient createClient(Config candidate) {
        Config snapshot = ConfigUtil.copy(candidate);
        return new DownloaderClient(create(snapshot), snapshot);
    }

    public static DownloaderClient createTestClient(Config candidate) {
        return createClient(candidate);
    }

    private static DownloadService downloadService() {
        try {
            return SpringUtil.getBean(DownloadService.class);
        } catch (RuntimeException ignored) {
            // Connection tests do not use qBittorrent's rename dependency.
            return new DownloadService();
        }
    }

}
