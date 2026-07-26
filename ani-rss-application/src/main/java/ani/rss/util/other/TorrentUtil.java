package ani.rss.util.other;

import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.FileUtils;
import ani.rss.commons.PinyinUtils;
import ani.rss.download.DownloaderClientFactory;
import ani.rss.download.DownloaderClient;
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
import java.util.List;

/**
 * 管理下载器的调用与种子存取
 */
@Slf4j
public class TorrentUtil {
    public static volatile DownloaderClient CLIENT;

    /**
     * 获取任务列表
     *
     * @return 种子列表
     */
    public static List<TorrentsInfo> getTorrentsInfos() {
        ThreadUtil.sleep(1000);
        List<TorrentsInfo> tasks = CLIENT.torrents().value();
        if (tasks == null) {
            return List.of();
        }
        ownershipService().observeTasks(tasks);
        if ("Aria2".equals(ConfigUtil.CONFIG.getDownloadToolType())) {
            return tasks.stream().filter(task -> ownershipService().findOwned(task).isPresent()).toList();
        }
        return tasks;
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
            FileUtil.del(saveTorrentFile);
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
        try {
            return CLIENT.connect(false).isSuccess();
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

        return List.of(
                TorrentsStateEnum.queuedUP,
                TorrentsStateEnum.uploading,
                TorrentsStateEnum.stalledUP,
                TorrentsStateEnum.stoppedUP
        ).contains(torrentsState);
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
        ani.rss.ownership.DownloadOwnership ownership = ownershipService.requireManagedTask(torrentsInfo);
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
        }

        log.info("删除任务 title:{} forcedDelete:{} deleteFiles:{}", name, forcedDelete, deleteFiles);

        ThreadUtil.sleep(500);
        String quarantineOperation = null;
        if (deleteFiles) {
            quarantineOperation = quarantineService().quarantineOwnership(ownership.ownershipId());
        }
        Boolean b = CLIENT.delete(torrentsInfo, false).isSuccess();
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
        ownershipService().requireOwned(torrentsInfo);
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
        Boolean renamed = CLIENT.rename(torrentsInfo).isSuccess();
        if (renamed) {
            addTags(torrentsInfo, TorrentsTagEnum.RENAME.getValue());
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
        ownershipService().requireOwned(torrentsInfo);
        String name = torrentsInfo.getName();
        log.debug("添加标签 {} {}", name, tags);
        boolean b = false;
        try {
            b = CLIENT.addTags(torrentsInfo, tags).isSuccess();
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
        ownershipService.requireOwned(torrentsInfo);
        try {
            log.info("修改保存位置 {} ==> {}", torrentsInfo.getName(), path);
            if (!CLIENT.setSavePath(torrentsInfo, path).isSuccess()) {
                throw new IllegalStateException("下载器拒绝修改保存位置");
            }
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    /**
     * 初始化下载工具
     */
    public static synchronized void loadDownloadTool() {
        Config config = ConfigUtil.CONFIG;
        String download = config.getDownloadToolType();

        if (download.equals("Alist")) {
            download = "OpenList";
            config.setDownloadToolType(download);
            ConfigUtil.sync();
        }

        CLIENT = DownloaderClientFactory.createClient(config);
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
        String hexHash = FileUtil.mainName(file);
        if (file.length() < 1) {
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

}
