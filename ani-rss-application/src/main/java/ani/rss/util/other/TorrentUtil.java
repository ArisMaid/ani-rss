package ani.rss.util.other;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.DownloaderClientFactory;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.ownership.OwnershipService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.bittorrent.TorrentFile;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 管理下载器的调用与种子存取
 */
@Slf4j
public class TorrentUtil {
    private static final Object CLIENT_LOCK = new Object();
    private static volatile DownloaderClient CLIENT;

    public static DownloaderClient client() {
        return CLIENT;
    }

    /**
     * 获取任务列表
     *
     * @return 种子列表
     */
    public static List<TorrentsInfo> getTorrentsInfos() {
        DownloaderResult<List<TorrentsInfo>> result = getTorrentsInfosResult();
        return result.isSuccess() && result.value() != null
                ? new ArrayList<>(result.value())
                : new ArrayList<>();
    }

    public static DownloaderResult<List<TorrentsInfo>> getTorrentsInfosResult() {
        ThreadUtil.sleep(1000);
        DownloaderClient client = CLIENT;
        if (client == null) {
            return DownloaderResult.failed("DOWNLOADER_NOT_INITIALIZED", false);
        }
        DownloaderResult<List<TorrentsInfo>> result = client.torrents();
        if (!result.isSuccess()) {
            return result;
        }
        List<TorrentsInfo> tasks = result.value() == null ? List.of() : result.value();
        String downloaderType = client.configurationSnapshot().getDownloadToolType();
        ownershipService().observeTasks(downloaderType, tasks);
        // Downstream callers can rename, tag, move, or delete a task. Keep
        // unverified candidates in the ownership workflow only, never in the
        // operational task stream.
        tasks = tasks.stream()
                .filter(task -> ownershipService().findOwned(downloaderType, task).isPresent())
                .toList();
        return DownloaderResult.success(new ArrayList<>(tasks));
    }

    /**
     * 获取种子存放文件夹
     *
     * @param ani 订阅
     * @return 文件夹
     */
    public static File getTorrentDir(Ani ani) {
        String title = ani.getTitle();
        Boolean ova = ani.getOva();
        Integer season = ani.getSeason();

        File configDir = ConfigUtil.getConfigDir();

        String s = PinyinUtils.getPinyinInitialLetters(title);

        File torrents = new File(StrFormatter.format("{}/torrents/{}/Season {}", configDir, title, season));
        if (!torrents.exists()) {
            torrents = new File(StrFormatter.format("{}/torrents/{}/{}/Season {}", configDir, s, title, season));
        }
        if (ova) {
            torrents = new File(StrFormatter.format("{}/torrents/{}", configDir, title));
            if (!torrents.exists()) {
                torrents = new File(StrFormatter.format("{}/torrents/{}/{}", configDir, s, title));
            }
        }
        return torrents;
    }

    /**
     * 获取种子
     *
     * @param ani  订阅
     * @param item 资源项
     * @return 种子文件
     */
    public static File getTorrent(Ani ani, Item item) {
        String infoHash = item.getInfoHash();
        File torrents = getTorrentDir(ani);
        String torrent = item.getTorrent();
        if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)
                || ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
            return new File(torrents, infoHash + ".txt");
        }
        return new File(torrents, infoHash + ".torrent");
    }

    /**
     * 下载种子文件
     *
     * @param ani  订阅
     * @param item 资源项
     * @return 种子文件
     */
    public static File saveTorrent(Ani ani, Item item) {
        String torrent = item.getTorrent();
        String reName = item.getReName();

        log.info("下载种子 {}", reName);
        File saveTorrentFile = getTorrent(ani, item);
        if (saveTorrentFile.exists()) {
            return saveTorrentFile;
        }

        try {
            if (ReUtil.contains(StringEnum.MAGNET_REG, torrent)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                log.info("种子下载完成 {}", reName);
                return saveTorrentFile;
            }

            if (ReUtil.contains(StringEnum.ED2K_REG, torrent)) {
                FileUtil.writeUtf8String(torrent, saveTorrentFile);
                log.info("种子下载完成 {}", reName);
                return saveTorrentFile;
            }

            return HttpReq.get(torrent)
                    .thenFunction(res -> {
                        int status = res.getStatus();
                        if (status == 404) {
                            // 如果为 404 则写入空文件 已在 getMagnet 处理过
                            FileUtil.writeUtf8String("", saveTorrentFile);
                            log.info("种子下载完成 {}", reName);
                            return saveTorrentFile;
                        }
                        HttpReq.assertStatus(res);
                        FileUtil.writeFromStream(res.bodyStream(), saveTorrentFile, true);
                        log.info("种子下载完成 {}", reName);
                        return saveTorrentFile;
                    });
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.error("下载种子时出现问题 {}", message);
            log.error(message, e);
            // 种子未下载异常，删除
            FileUtils.deleteRegularFile(saveTorrentFile);
        }
        return saveTorrentFile;
    }

    /**
     * 登录 qBittorrent
     *
     * @return 是否登录成功
     */
    public static Boolean login() {
        ThreadUtil.sleep(1000);
        Config config = ConfigUtil.CONFIG;
        String downloadPath = config.getDownloadPathTemplate();
        if (StrUtil.isBlank(downloadPath)) {
            log.warn("下载位置未设置");
            return false;
        }
        DownloaderClient activeClient = CLIENT;
        if (activeClient == null) {
            log.warn("下载工具尚未初始化");
            return false;
        }
        try {
            return activeClient.connect(false).isSuccess();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 判断种子是否可以删除
     *
     * @param torrentsInfo 种子信息
     * @return 是否可以删除
     */
    public static Boolean allowDelete(TorrentsInfo torrentsInfo) {
        Config config = ConfigUtil.CONFIG;
        Boolean awaitStalledUP = config.getAwaitStalledUP();

        TorrentsStateEnum torrentsState = torrentsInfo.getState();

        // 是否等待做种完毕
        if (awaitStalledUP) {
            return torrentsState == TorrentsStateEnum.stoppedUP;
        }

        return torrentsInfo.finished();
    }


    /**
     * 删除已完成任务
     *
     * @param torrentsInfo 任务
     * @param forcedDelete 强制删除
     * @param deleteFiles  删除本地文件
     */
    public static Boolean delete(TorrentsInfo torrentsInfo, Boolean forcedDelete, Boolean deleteFiles) {
        OwnershipService ownershipService = ownershipService();
        DownloaderClient activeClient = CLIENT;
        if (activeClient == null) {
            log.error("删除任务失败 code:DOWNLOADER_NOT_INITIALIZED");
            return false;
        }
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        ani.rss.ownership.DownloadOwnership ownership =
                ownershipService.requireManagedTask(downloaderType, torrentsInfo);
        String name = torrentsInfo.getName();

        if (!forcedDelete) {
            Config config = ConfigUtil.CONFIG;
            Boolean delete = config.getDelete();

            if (!delete) {
                return false;
            }

            if (!allowDelete(torrentsInfo)) {
                return false;
            }

            // qBittorrent can finish a small torrent in the same loop that
            // performs its rename. Persist and verify the final paths before
            // removing the only remaining remote de-duplication evidence.
            if ("qBittorrent".equalsIgnoreCase(downloaderType) &&
                    !ownershipService.captureAndVerifyFiles(ownership.ownershipId(), torrentsInfo)) {
                log.warn("保留已完成 qBittorrent 任务，本地文件清单尚不可验证 {}", name);
                return false;
            }
        }

        log.info("删除任务 title:{} forcedDelete:{} deleteFiles:{}", name, forcedDelete, deleteFiles);

        ThreadUtil.sleep(500);
        String quarantineOperation = null;
        if (deleteFiles) {
            quarantineOperation = quarantineService().quarantineOwnership(ownership.ownershipId());
        }
        Boolean b = activeClient.delete(torrentsInfo, false).isSuccess();
        if (!b) {
            if (quarantineOperation != null) {
                quarantineService().restore(quarantineOperation);
            }
            log.error("删除任务失败 {}", name);
            return false;
        }
        log.info("删除任务成功 {}", name);
        return true;
    }


    /**
     * 删除已完成任务
     *
     * @param torrentsInfo 种子信息
     * @return 是否删除成功
     */
    public static Boolean delete(TorrentsInfo torrentsInfo) {
        return delete(torrentsInfo, false, false);
    }

    /**
     * 重命名
     *
     * @param torrentsInfo 种子信息
     */
    public static void rename(TorrentsInfo torrentsInfo) {
        DownloaderClient activeClient = CLIENT;
        if (activeClient == null) {
            throw new IllegalStateException("downloader is not initialized");
        }
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        ani.rss.ownership.DownloadOwnership ownership =
                ownershipService().requireOwned(downloaderType, torrentsInfo);
        Config config = ConfigUtil.CONFIG;
        Boolean rename = config.getRename();
        if (!rename) {
            return;
        }

        List<String> tags = torrentsInfo.getTagList();
        if (tags.contains(TorrentsTagEnum.RENAME.getValue())) {
            return;
        }

        ThreadUtil.sleep(1000);
        Boolean renamed = activeClient.rename(torrentsInfo).isSuccess();
        if (renamed) {
            addTags(torrentsInfo, TorrentsTagEnum.RENAME.getValue());
            ownershipService().captureFiles(ownership.ownershipId(), torrentsInfo);
        }
    }

    /**
     * 添加标签
     *
     * @param torrentsInfo 种子信息
     * @param tags         标签
     * @return 是否添加成功
     */
    public static Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        if (StrUtil.isBlank(tags)) {
            return false;
        }
        DownloaderClient activeClient = CLIENT;
        if (activeClient == null) {
            return false;
        }
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        ownershipService().requireOwned(downloaderType, torrentsInfo);
        String name = torrentsInfo.getName();
        log.debug("添加标签 {} {}", name, tags);
        boolean b = false;
        try {
            b = activeClient.addTags(torrentsInfo, tags).isSuccess();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return b;
    }


    /**
     * 修改保存位置
     *
     * @param torrentsInfo 种子信息
     * @param path         保存路径
     */
    public static void setSavePath(TorrentsInfo torrentsInfo, String path) {
        if (StrUtil.isBlank(path)) {
            return;
        }
        OwnershipService ownershipService = ownershipService();
        DownloaderClient activeClient = CLIENT;
        if (activeClient == null) {
            throw new IllegalStateException("downloader is not initialized");
        }
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        ownershipService.requireOwned(downloaderType, torrentsInfo);
        log.info("修改保存位置 {} ==> {}", torrentsInfo.getName(), path);
        if (!activeClient.setSavePath(torrentsInfo, path).isSuccess()) {
            throw new IllegalStateException("downloader rejected the save-path change");
        }
    }

    /**
     * 初始化下载工具
     */
    public static void loadDownloadTool() {
        Config config = ConfigUtil.snapshot();
        String download = config.getDownloadToolType();
        if ("Alist".equals(download)) {
            download = "OpenList";
            config.setDownloadToolType(download);
            ConfigUtil.sync(config);
        }

        DownloaderClient replacement = DownloaderClientFactory.createClient(config);
        synchronized (CLIENT_LOCK) {
            CLIENT = replacement;
        }
        log.info("下载工具 {}", download);
    }

    private static OwnershipService ownershipService() {
        return SpringUtil.getBean(OwnershipService.class);
    }

    private static ani.rss.ownership.QuarantineService quarantineService() {
        return SpringUtil.getBean(ani.rss.ownership.QuarantineService.class);
    }

    /**
     * 通过种子获取到磁力链接
     *
     * @param file 文件
     * @return 磁力链接
     */
    public static String getMagnet(File file) {
        String hexHash = getInfoHash(file);
        if (file == null || file.length() < 1) {
            return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
        }
        String extName = FileUtil.extName(file);
        if ("txt".equals(extName)) {
            return FileUtil.readUtf8String(file);
        }
        try {
            TorrentFile torrentFile = new TorrentFile(file);
            hexHash = torrentFile.getHexHash();
        } catch (Exception e) {
            log.error("转换种子为磁力链接时出现错误 {}", FileUtils.getAbsolutePath(file));
            log.error(e.getMessage(), e);
        }
        return StrFormatter.format("magnet:?xt=urn:btih:{}", hexHash);
    }

    /**
     * Resolve the canonical BitTorrent info-hash for a cached input.
     *
     * RSS enclosure filenames are opaque in many feeds, so treating a local
     * filename as the remote task hash breaks ownership reconciliation.  A
     * magnet cache already contains its hash; a .torrent cache must be parsed.
     */
    public static String getInfoHash(File file) {
        String canonical = getCanonicalInfoHash(file);
        if (StrUtil.isNotBlank(canonical)) {
            return canonical;
        }
        if (file == null) {
            return "";
        }
        return FileUtil.mainName(file).trim().toLowerCase();
    }

    /**
     * Returns a parsed BitTorrent identity only. Unlike {@link #getInfoHash(File)},
     * this method never treats an opaque RSS cache filename as an info-hash.
     */
    public static String getCanonicalInfoHash(File file) {
        if (file == null) {
            return "";
        }
        if (!file.isFile() || file.length() < 1) {
            return "";
        }
        try {
            String extension = FileUtil.extName(file);
            if ("torrent".equalsIgnoreCase(extension)) {
                synchronized (TorrentFile.class) {
                    return new TorrentFile(file).getHexHash().toLowerCase();
                }
            }
            if ("txt".equalsIgnoreCase(extension)) {
                String magnet = FileUtil.readUtf8String(file);
                String hash = ReUtil.get("(?i)btih:([a-z0-9]+)", magnet, 1);
                if (StrUtil.isNotBlank(hash)) {
                    return hash.toLowerCase();
                }
            }
        } catch (Exception e) {
            log.warn("解析缓存种子的 info-hash 失败 type:{}", e.getClass().getSimpleName());
        }
        return "";
    }

}
