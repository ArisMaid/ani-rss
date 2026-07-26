package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public interface BaseDownload {
    /**
     * 登录
     *
     * @param config 设置
     * @return 登录状态
     */
    default Boolean login(Config config) {
        return login(false, config);
    }

    /**
     * 登录
     *
     * @param test   测试登录
     * @param config 设置
     * @return 登录状态
     */
    Boolean login(Boolean test, Config config);

    default DownloaderResult<Void> connectResult(Boolean test, Config config) {
        return Boolean.TRUE.equals(login(test, config))
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("CONNECTION_REJECTED");
    }

    /**
     * 获取任务列表
     *
     * @return 任务列表
     */
    List<TorrentsInfo> getTorrentsInfos();

    default DownloaderResult<List<TorrentsInfo>> torrentsResult() {
        return DownloaderResult.success(List.copyOf(getTorrentsInfos()));
    }

    /**
     * 下载
     *
     * @param ani         订阅
     * @param item        下载项
     * @param savePath    保存位置
     * @param torrentFile 种子文件
     * @return 下载状态
     */
    Boolean download(Ani ani, Item item, String savePath, File torrentFile);

    default DownloaderResult<Void> downloadResult(
            Ani ani, Item item, String savePath, File torrentFile) {
        return Boolean.TRUE.equals(download(ani, item, savePath, torrentFile))
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("DOWNLOAD_REJECTED");
    }

    /**
     * 删除已完成任务
     *
     * @param torrentsInfo 任务
     * @param deleteFiles  删除本地文件
     * @return 删除状态
     */
    Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles);

    default DownloaderResult<Void> deleteResult(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        return Boolean.TRUE.equals(delete(torrentsInfo, deleteFiles))
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("DELETE_REJECTED");
    }

    /**
     * 重命名
     *
     * @param torrentsInfo 任务
     */
    Boolean rename(TorrentsInfo torrentsInfo);

    default DownloaderResult<Void> renameResult(TorrentsInfo torrentsInfo) {
        return Boolean.TRUE.equals(rename(torrentsInfo))
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("RENAME_REJECTED");
    }

    /**
     * 为任务添加标签
     *
     * @param torrentsInfo 任务
     * @param tags         标签
     * @return 状态
     */
    Boolean addTags(TorrentsInfo torrentsInfo, String tags);

    default DownloaderResult<Void> addTagsResult(TorrentsInfo torrentsInfo, String tags) {
        return Boolean.TRUE.equals(addTags(torrentsInfo, tags))
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("TAG_REJECTED");
    }

    /**
     * 自动更新 Trackers
     *
     * @param trackers trackers 列表
     */
    void updateTrackers(Set<String> trackers);

    default DownloaderResult<Void> updateTrackersResult(Set<String> trackers) {
        updateTrackers(trackers);
        return DownloaderResult.success(null);
    }

    /**
     * 修改保存位置
     *
     * @param torrentsInfo 任务
     * @param path         位置
     */
    void setSavePath(TorrentsInfo torrentsInfo, String path);

    default DownloaderResult<Void> setSavePathResult(TorrentsInfo torrentsInfo, String path) {
        setSavePath(torrentsInfo, path);
        return DownloaderResult.success(null);
    }

    /**
     * 获取重命名结果
     *
     * @param name   文件名
     * @param reName 重命名
     * @return 最终命名
     */
    default String getFileReName(String name, String reName) {
        String ext = FileUtil.extName(name);
        if (StrUtil.isBlank(ext)) {
            return name;
        }
        String newPath = reName;
        if (FileUtils.isVideoFormat(ext)) {
            newPath = newPath + "." + ext;
        } else if (FileUtils.isSubtitleFormat(ext)) {
            String s = FileUtil.extName(FileUtil.mainName(name));
            if (StrUtil.isNotBlank(s)) {
                newPath = newPath + "." + s;
            }
            newPath = newPath + "." + ext;
        } else {
            return name;
        }

        if (name.equals(newPath)) {
            return name;
        }
        return newPath;
    }

    /**
     * 获取新任务的tag
     *
     * @param ani  订阅
     * @param item 资源项
     * @return tags
     */
    default List<String> newTags(Ani ani, Item item) {
        return newTags(ani, item, ConfigUtil.copy(ConfigUtil.CONFIG));
    }

    /** Build tags from the immutable configuration snapshot owned by this client. */
    default List<String> newTags(Ani ani, Item item, Config config) {
        Boolean master = item.getMaster();
        String subgroup = item.getSubgroup();
        subgroup = StrUtil.blankToDefault(subgroup, "未知字幕组");

        List<String> tags = new ArrayList<>();

        tags.add(TorrentsTagEnum.ANI_RSS.getValue());
        if (StrUtil.isNotBlank(ani.getId())) {
            tags.add(ani.getId());
        }
        tags.add(subgroup);
        if (!master) {
            tags.add(TorrentsTagEnum.STANDBY_RSS.getValue());
        }

        Boolean customTagsEnable = ani.getCustomTagsEnable();

        if (customTagsEnable) {
            // 获取订阅自定义标签
            List<String> aniCustomTags = ani.getCustomTags();
            if (CollectionUtil.isNotEmpty(aniCustomTags)) {
                tags.addAll(aniCustomTags);
            }
            return tags;
        }

        // 获取全局自定义标签
        List<String> globalCustomTags = config.getCustomTags();
        if (CollectionUtil.isNotEmpty(globalCustomTags)) {
            tags.addAll(globalCustomTags);
        }

        return tags;
    }
}
