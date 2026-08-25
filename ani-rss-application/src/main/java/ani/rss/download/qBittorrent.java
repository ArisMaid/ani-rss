package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.entity.torrent.qBittorrentTorrentsInfo;
import ani.rss.enums.StringEnum;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.ScopedCookieJar;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.function.Function;

/**
 * qBittorrent
 */
@Slf4j
public class qBittorrent implements BaseDownload {

    private final DownloadService downloadService;
    private final Config config;
    private final ScopedCookieJar cookies = new ScopedCookieJar();

    public qBittorrent(DownloadService downloadService) {
        this(downloadService, ConfigUtil.snapshot());
    }

    public qBittorrent(DownloadService downloadService, Config config) {
        this.downloadService = downloadService;
        this.config = ConfigUtil.copy(config);
    }

    /**
     * 获取对应任务的文件列表
     *
     * @param torrentsInfo 种子信息
     * @param filter       过滤出视频与字幕
     * @param config       设置
     * @return 文件列表
     */
    public List<qBittorrentTorrentsInfo.FileEntity> files(TorrentsInfo torrentsInfo, Boolean filter) {
        String hash = torrentsInfo.getHash();

        return execute(getApi("/api/v2/torrents/files")
                .form("hash", hash)
                , res -> {
                    requireSuccess(res);
                    return GsonStatic.fromJsonList(res.body(), qBittorrentTorrentsInfo.FileEntity.class).stream()
                            .filter(fileEntity -> {
                                if (!filter) {
                                    return true;
                                }
                                String name = fileEntity.getName();
                                String extName = FileUtil.extName(name);
                                if (StrUtil.isBlank(extName)) {
                                    return false;
                                }
                                extName = extName.toLowerCase();
                                Long size = fileEntity.getSize();
                                if (size < 1) {
                                    return false;
                                }
                                return FileUtils.isVideoFormat(extName) || FileUtils.isSubtitleFormat(extName);
                            })
                            .sorted((fileEntity1, fileEntity2) -> Long.compare(fileEntity2.getSize(), fileEntity1.getSize()))
                            .toList();
                });
    }

    @Override
    public Boolean login(Boolean test, Config ignored) {
        String host = config.getDownloadToolHost();
        String username = config.getDownloadToolUsername();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(password) ||
                (!usesApiKey() && StrUtil.isBlank(username))) {
            log.warn("qBittorrent 未配置完成");
            return false;
        }

        if (usesApiKey()) {
            return execute(postApi("/api/v2/app/version"), response -> {
                if (response.getStatus() == 401 || response.getStatus() == 403) {
                    throw DownloaderOperationException.rejected(
                            "QBITTORRENT_AUTHENTICATION_FAILED");
                }
                return requireSuccess(response);
            });
        }

        if (!test) {
            // A 401/403 means that a fresh login is needed; server failures must propagate.
            Boolean isOk = execute(postApi("/api/v2/app/version"), response -> {
                int status = response.getStatus();
                if (status == 401 || status == 403) {
                    return false;
                }
                return requireSuccess(response);
            });
            if (isOk) {
                return true;
            }
        }

        return execute(HttpReq.post(host + "/api/v2/auth/login", config)
                .form("username", username)
                .form("password", password)
                , res -> {
                    if (res.getStatus() == 401 || res.getStatus() == 403) {
                        throw DownloaderOperationException.rejected(
                                "QBITTORRENT_AUTHENTICATION_FAILED");
                    }
                    requireSuccess(res);
                    String body = res.body();
                    if (StrUtil.isBlank(body)) {
                        // qBittorrent 5.2+ can return an empty body after successful login.
                        return true;
                    }
                    if (!"Ok.".equalsIgnoreCase(body)) {
                        throw DownloaderOperationException.rejected(
                                "QBITTORRENT_AUTHENTICATION_FAILED");
                    }
                    return true;
                });
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        return downloadResult(ani, item, savePath, torrentFile).isSuccess();
    }

    @Override
    public DownloaderResult<Void> downloadResult(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();
        Boolean qbUseDownloadPath = config.getQbUseDownloadPath();

        List<String> tags = newTags(ani, item, config);
        String hash = StrUtil.blankToDefault(TorrentUtil.getInfoHash(torrentFile),
                StrUtil.blankToDefault(item.getInfoHash(), FileUtil.mainName(torrentFile)));

        Integer ratioLimit = config.getRatioLimit();
        Integer seedingTimeLimit = config.getSeedingTimeLimit();
        Integer inactiveSeedingTimeLimit = config.getInactiveSeedingTimeLimit();
        Boolean rename = config.getRename();

        Long upLimit = config.getUpLimit() * 1024;
        Long dlLimit = config.getDlLimit() * 1024;
        String qbContentLayout = StrUtil.blankToDefault(config.getQbContentLayout(), "Original");

        HttpRequest httpRequest = postApi("/api/v2/torrents/add")
                .form("addToTopOfQueue", false)
                .form("autoTMM", false)
                .form("category", TorrentsTagEnum.ANI_RSS.getValue())
                .form("contentLayout", qbContentLayout)
                .form("dlLimit", dlLimit)
                .form("firstLastPiecePrio", false)
                .form("rename", name)
                .form("savepath", savePath)
                .form("sequentialDownload", false)
                .form("skip_checking", false)
                .form("stopCondition", "None")
                .form("upLimit", upLimit)
                .form("useDownloadPath", qbUseDownloadPath)
                .form("tags", CollUtil.join(tags, ","))
                .form("ratioLimit", ratioLimit)
                .form("seedingTimeLimit", seedingTimeLimit)
                .form("inactiveSeedingTimeLimit", inactiveSeedingTimeLimit);

        String extName = FileUtil.extName(torrentFile);
        if ("txt".equals(extName)) {
            httpRequest
                    .form("paused", false)
                    .form("stopped", false)
                    .form("urls", FileUtil.readUtf8String(torrentFile));
        } else {
            if (torrentFile.length() > 0) {
                // 开启了重命名则在重命名后再开始下载
                httpRequest.form("paused", rename)
                        .form("stopped", rename)
                        .form("torrents", torrentFile);
            } else {
                httpRequest
                        .form("paused", false)
                        .form("stopped", false)
                        .form("urls", "magnet:?xt=urn:btih:" + FileUtil.mainName(torrentFile));
            }
        }
        try {
            execute(httpRequest, qBittorrent::requireSuccess);
        } catch (DownloaderOperationException exception) {
            if (!"QBITTORRENT_HTTP_409".equals(exception.errorCode())) {
                throw exception;
            }
            return recoverDuplicateSubmission(hash, tags, savePath);
        }
        return DownloaderResult.success(null, hash);
    }

    /**
     * qBittorrent uses 409 for a request that races with, or repeats after, a
     * prior accepted add.  Recover only after proving that the remote task is
     * the exact ANI-RSS submission; a same-hash task from another application
     * must remain untouched.
     */
    private DownloaderResult<Void> recoverDuplicateSubmission(
            String hash, List<String> expectedTags, String expectedSavePath) {
        DownloaderResult<TorrentsInfo> existingResult = findTorrentByHash(hash);
        if (!existingResult.isSuccess()) {
            if (existingResult.retryable()) {
                return DownloaderResult.failed(existingResult.errorCode(), true);
            }
            return DownloaderResult.rejected("QBITTORRENT_DUPLICATE_UNVERIFIED");
        }
        TorrentsInfo existing = existingResult.value();
        if (existing == null || !isVerifiedDuplicate(existing, hash, expectedTags, expectedSavePath)) {
            return DownloaderResult.rejected("QBITTORRENT_DUPLICATE_UNOWNED");
        }
        log.info("qBittorrent duplicate submission verified hash:{}", hash);
        return DownloaderResult.success(null, hash);
    }

    private DownloaderResult<TorrentsInfo> findTorrentByHash(String hash) {
        if (StrUtil.isBlank(hash)) {
            return DownloaderResult.rejected("QBITTORRENT_INVALID_INFO_HASH");
        }
        try {
            return execute(getApi("/api/v2/torrents/info")
                    .form("hashes", hash), response -> {
                requireSuccess(response);
                return GsonStatic.fromJsonList(response.body(), qBittorrentTorrentsInfo.class).stream()
                        // The ownership manifest must describe every selected
                        // torrent payload, not just media files.  Otherwise
                        // artwork, fonts, and README files survive a
                        // subscription deletion and prevent its now-empty
                        // directory from being removed.  Rename still uses
                        // its own media-only query below.
                        .map(value -> value.toTorrentsInfo(task -> files(task, false)))
                        .filter(task -> StrUtil.equalsIgnoreCase(hash, task.getHash()))
                        .findFirst()
                        .map(DownloaderResult::success)
                        .orElseGet(() -> DownloaderResult.rejected("QBITTORRENT_TASK_NOT_FOUND"));
            });
        } catch (Exception exception) {
            return DownloaderFailures.result(exception);
        }
    }

    private static boolean isVerifiedDuplicate(
            TorrentsInfo task, String hash, List<String> expectedTags, String expectedSavePath) {
        if (!StrUtil.equalsIgnoreCase(hash, task.getHash()) ||
                !TorrentsTagEnum.ANI_RSS.getValue().equals(task.getCategory())) {
            return false;
        }
        List<String> actualTags = task.getTagList();
        if (actualTags == null || !actualTags.containsAll(expectedTags)) {
            return false;
        }
        return StrUtil.equals(FileUtils.getAbsolutePath(expectedSavePath),
                FileUtils.getAbsolutePath(task.getSavePath()));
    }

    /**
     * 开始下载
     *
     * @param torrentsInfo 种子信息
     * @param config       设置
     * @return 下载是否成功
     */
    public Boolean start(TorrentsInfo torrentsInfo) {
        boolean b = execute(postApi("/api/v2/torrents/start")
                .form("hashes", torrentsInfo.getHash())
                , qBittorrent::requireSuccess);
        if (b) {
            return true;
        }

        return execute(postApi("/api/v2/torrents/resume")
                .form("hashes", torrentsInfo.getHash())
                , qBittorrent::requireSuccess);
    }

    @Override
    public DownloaderResult<Void> recoverResult(TorrentsInfo torrentsInfo) {
        String hash = torrentsInfo == null ? "" : torrentsInfo.getHash();
        if (StrUtil.isBlank(hash)) {
            return DownloaderResult.rejected("QBITTORRENT_RECOVERY_HASH_MISSING");
        }
        boolean checked = execute(postApi("/api/v2/torrents/recheck")
                .form("hashes", hash), qBittorrent::requireSuccess);
        if (!checked) {
            return DownloaderResult.rejected("QBITTORRENT_RECHECK_REJECTED");
        }
        return start(torrentsInfo)
                ? DownloaderResult.success(null)
                : DownloaderResult.rejected("QBITTORRENT_RESUME_REJECTED");
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return execute(getApi("/api/v2/torrents/info"), res -> {
                    requireSuccess(res);
                    List<qBittorrentTorrentsInfo> torrentsInfos = GsonStatic.fromJsonList(res.body(), qBittorrentTorrentsInfo.class);
                    return torrentsInfos.stream()
                            .map(value -> value.toTorrentsInfo(task -> files(task, false)))
                            .filter(torrentsInfo -> {
                                // 过滤出 ani-rss 标签或分类
                                String category = torrentsInfo.getCategory();
                                if (TorrentsTagEnum.ANI_RSS.getValue().equals(category)) {
                                    return true;
                                }

                                List<String> tagList = torrentsInfo.getTagList();
                                return tagList != null && tagList.contains(TorrentsTagEnum.ANI_RSS.getValue());
                            })
                            .toList();
                });
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String hash = torrentsInfo.getHash();
        return execute(postApi("/api/v2/torrents/delete")
                .form("hashes", hash)
                .form("deleteFiles", false), qBittorrent::requireSuccess);
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String reName = torrentsInfo.getName();

        if (StrUtil.isBlank(reName) || !ReUtil.contains(StringEnum.SEASON_REG, reName)) {
            // 剧场版 OR OVA 直接开始任务
            Boolean start = start(torrentsInfo);
            Assert.isTrue(start, "开始任务失败 {}", reName);
            if (start) {
                log.info("开始任务 {}", reName);
            }
            return true;
        }

        String hash = torrentsInfo.getHash();

        Optional<Ani> aniOpt = downloadService.findAniByDownloadPath(torrentsInfo);

        if (aniOpt.isEmpty()) {
            log.error("未能获取番剧对象: {}", torrentsInfo.getName());
            return false;
        }

        Ani ani = aniOpt.get();

        List<String> priorityKeywords = getPriorityKeywords(config, ani);

        List<qBittorrentTorrentsInfo.FileEntity> files = files(torrentsInfo, true);

        if (!priorityKeywords.isEmpty()) {
            files = files.stream()
                    .sorted(Comparator.comparingInt(file -> {
                        String fileName = file.getName();
                        String mainName = FileUtil.mainName(fileName);
                        int minIndex = Integer.MAX_VALUE;
                        for (int i = 0; i < priorityKeywords.size(); i++) {
                            String priorityKeyword = priorityKeywords.get(i);
                            if (!mainName.contains(priorityKeyword)) {
                                continue;
                            }
                            minIndex = Math.min(minIndex, i);
                        }
                        return minIndex;
                    }))
                    .toList();
        }

        List<String> names = files.stream()
                .map(qBittorrentTorrentsInfo.FileEntity::getName)
                .toList();

        if (files.isEmpty()) {
            log.debug("{} 磁力链接还在获取原数据中", hash);
            return false;
        }

        Boolean subtitleIndependentFolderEnabled = config.getSubtitleIndependentFolderEnabled();
        String subtitleIndependentFolderName = config.getSubtitleIndependentFolderName();

        List<String> newNames = new ArrayList<>();

        for (qBittorrentTorrentsInfo.FileEntity fileEntity : files) {
            String name = fileEntity.getName();
            String newPath = getFileReName(name, reName);

            if (
                    FileUtils.isSubtitleFormat(newPath) &&
                            subtitleIndependentFolderEnabled &&
                            StrUtil.isNotBlank(subtitleIndependentFolderName)
            ) {
                // 字幕独立文件夹
                newPath = subtitleIndependentFolderName + "/" + newPath;
            }

            if (names.contains(newPath)) {
                continue;
            }
            if (newNames.contains(newPath)) {
                // 停止不必要的文件下载
                setFilePriority(hash, fileEntity.getIndex(), 0);
                continue;
            }
            newNames.add(newPath);

            // 文件名未发生改变
            if (name.equals(newPath)) {
                continue;
            }

            log.info("重命名 {} ==> {}", name, newPath);

            Boolean b = renameFile(hash, name, newPath);
            Assert.isTrue(b, "重命名失败 {} ==> {}", name, newPath);
        }

        Boolean start = start(torrentsInfo);
        Assert.isTrue(start, "开始任务失败 {}", reName);
        log.info("开始任务 {}", reName);

        if (newNames.isEmpty()) {
            return true;
        }

        // qb重命名具有延迟，等待重命名完成
        for (int i = 0; i < 10; i++) {
            ThreadUtil.sleep(1000);
            names = torrentsInfo.getFilesSupplier().get();
            if (new HashSet<>(names).containsAll(newNames)) {
                return true;
            }
        }

        log.warn("重命名貌似出现了问题？{}", reName);
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        String hash = torrentsInfo.getHash();
        return execute(postApi("/api/v2/torrents/addTags")
                .form("hashes", hash)
                .form("tags", tags)
                , qBittorrent::requireSuccess);
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        JsonObject preferences = execute(getApi("/api/v2/app/preferences"), res -> {
                    requireSuccess(res);
                    String body = res.body();
                    return GsonStatic.fromJson(body, JsonObject.class);
                });

        preferences.addProperty("add_trackers", CollUtil.join(trackers, "\n"));
        preferences.addProperty("add_trackers_enabled", true);

        boolean updated = execute(postApi("/api/v2/app/setPreferences")
                .form("json", GsonStatic.toJson(preferences))
                , qBittorrent::requireSuccess);
        Assert.isTrue(updated, "qBittorrent 更新 Trackers 失败");
        log.info("qBittorrent 更新Trackers完成 共{}条", trackers.size());

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        boolean autoManagement = execute(postApi("/api/v2/torrents/setAutoManagement")
                .form("hashes", torrentsInfo.getHash())
                .form("enable", false)
                , qBittorrent::requireSuccess);
        Assert.isTrue(autoManagement, "qBittorrent 关闭自动管理失败");
        boolean savePathUpdated = execute(postApi("/api/v2/torrents/setSavePath")
                .form("hashes", torrentsInfo.getHash())
                .form("path", path)
                , qBittorrent::requireSuccess);
        Assert.isTrue(savePathUpdated, "qBittorrent 修改保存位置失败");
    }

    public Boolean setFilePriority(String hash, Integer index, int priority) {
        return execute(postApi("/api/v2/torrents/filePrio")
                        .form("hash", hash)
                        .form("id", index)
                        .form("priority", priority),
                qBittorrent::requireSuccess);
    }

    public Boolean renameFile(String hash, String oldPath, String newPath) {
        return execute(postApi("/api/v2/torrents/renameFile")
                        .form("hash", hash)
                        .form("oldPath", oldPath)
                        .form("newPath", newPath),
                qBittorrent::requireSuccess);
    }

    public Boolean addCollection(String name, File torrentFile, String savePath, List<String> tags) {
        long upLimit = config.getUpLimit() * 1024L;
        long dlLimit = config.getDlLimit() * 1024L;
        String qbContentLayout = StrUtil.blankToDefault(config.getQbContentLayout(), "Original");
        return execute(postApi("/api/v2/torrents/add")
                        .form("torrents", torrentFile)
                        .form("addToTopOfQueue", false)
                        .form("autoTMM", false)
                        .form("category", "")
                        .form("contentLayout", qbContentLayout)
                        .form("dlLimit", dlLimit)
                        .form("firstLastPiecePrio", false)
                        .form("paused", true)
                        .form("stopped", true)
                        .form("rename", name)
                        .form("savepath", savePath)
                        .form("sequentialDownload", false)
                        .form("skip_checking", false)
                        .form("stopCondition", "None")
                        .form("upLimit", upLimit)
                        .form("useDownloadPath", config.getQbUseDownloadPath())
                        .form("tags", CollUtil.join(tags, ","))
                        .form("ratioLimit", config.getRatioLimit())
                        .form("seedingTimeLimit", config.getSeedingTimeLimit())
                        .form("inactiveSeedingTimeLimit", config.getInactiveSeedingTimeLimit()),
                qBittorrent::requireSuccess);
    }

    private <T> T execute(HttpRequest request, Function<HttpResponse, T> handler) {
        return cookies.execute(request, handler);
    }

    private HttpRequest getApi(String path) {
        return authorize(HttpReq.get(config.getDownloadToolHost() + path, config));
    }

    private HttpRequest postApi(String path) {
        return authorize(HttpReq.post(config.getDownloadToolHost() + path, config));
    }

    private HttpRequest authorize(HttpRequest request) {
        if (usesApiKey()) {
            request.header("Authorization", "Bearer " + config.getDownloadToolPassword());
        }
        return request;
    }

    private boolean usesApiKey() {
        return StrUtil.startWithIgnoreCase(config.getDownloadToolPassword(), "qbt_");
    }

    private static boolean requireSuccess(HttpResponse response) {
        int status = response.getStatus();
        if (status < 200 || status >= 300) {
            throw DownloaderOperationException.http("QBITTORRENT", status);
        }
        return true;
    }

    private static List<String> getPriorityKeywords(Config config, Ani ani) {
        Boolean priorityKeywordsEnable = config.getPriorityKeywordsEnable();
        Boolean customPriorityKeywordsEnable = ani.getCustomPriorityKeywordsEnable();

        if (customPriorityKeywordsEnable) {
            return ani.getCustomPriorityKeywords();
        }

        if (priorityKeywordsEnable) {
            return config.getPriorityKeywords();
        }

        return new ArrayList<>();
    }


}
