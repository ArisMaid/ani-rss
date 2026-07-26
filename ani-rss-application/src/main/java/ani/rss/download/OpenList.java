package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.*;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.entity.web.Header;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;
import java.util.stream.Stream;

@Slf4j
public class OpenList implements BaseDownload {
    private Config config;

    public OpenList() {
    }

    public OpenList(Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return false;
        }
        String downloadPath = config.getDownloadPathTemplate();
        Assert.notBlank(downloadPath, "未设置下载位置");
        String provider = config.getProvider();
        Assert.notBlank(provider, "请选择 Driver");
        try {
            executeApi(getApi("me"), "login");
            return true;
        } catch (Exception e) {
            log.warn("登录 OpenList 失败 type:{}", e.getClass().getSimpleName());
        }
        return false;
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return List.of();
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        // windows 真该死啊
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String reName = item.getReName();
        String path = savePath + "/" + reName;
        Boolean delete = config.getDelete();
        try {
            mkdir(path);
            String tid;
            try {
                tid = fsAddOfflineDownload(magnet, path);
                log.info("添加离线下载成功 {}", reName);
            } catch (Exception e) {
                log.error("添加离线下载失败 {}", reName);
                throw new IllegalStateException("添加离线下载失败 " + reName);
            }

            // 记录开始时间
            DateTime startTime = DateTime.now();

            // 重试次数
            long retry = 0;
            while (true) {
                Integer openListDownloadTimeout = config.getOpenListDownloadTimeout();
                Long openListDownloadRetryNumber = config.getOpenListDownloadRetryNumber();

                DateTime endTime = DateUtil.offsetMinute(startTime, openListDownloadTimeout);
                DateTime currentTime = DateTime.now();
                if (currentTime.getTime() >= endTime.getTime()) {
                    // 超过下载超时限制
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, openListDownloadTimeout);
                    return false;
                }

                Optional<OpenListTaskInfo> taskInfoOpt = taskInfo(tid);

                if (taskInfoOpt.isEmpty()) {
                    continue;
                }

                OpenListTaskInfo taskInfo = taskInfoOpt.get();
                OpenListTaskInfo.State state = taskInfo.getState();
                String error = taskInfo.getError();

                // errored 重试
                if (
                        List.of(
                                OpenListTaskInfo.State.Error,
                                OpenListTaskInfo.State.Failing,
                                OpenListTaskInfo.State.Failed
                        ).contains(state)
                ) {
                    // 已到达最大重试次数 5 次, -1 不限制
                    if (openListDownloadRetryNumber > -1) {
                        if (retry >= openListDownloadRetryNumber) {
                            // bug fix: 新资源下载完成后，OpenList 状态可能未及时刷新
                            // 此处通过检查文件是否存在来兜底，存在则直接继续后续逻辑
                            Optional<OpenListFileInfo> first = findFiles(path).stream()
                                    .filter(openListFileInfo -> FileUtils.isVideoFormat(openListFileInfo.getName()))
                                    .findFirst();
                            if (first.isPresent()) {
                                log.info("资源已下载完毕，OpenList 可能处于卡死状态，此处跳过");
                                break;
                            }
                            log.error("离线下载失败 {}", error);
                            return false;
                        }
                        retry++;
                        log.info("离线任务正在进行重试 {}, 当前重试次数 {}, 最大重试次数 {}", tid, retry, openListDownloadRetryNumber);
                    }
                    taskRetry(tid);
                    continue;
                }

                if (
                        List.of(
                                OpenListTaskInfo.State.Canceling,
                                OpenListTaskInfo.State.Canceled
                        ).contains(state)
                ) {
                    log.error("离线任务已被取消 {}", reName);
                    return false;
                }

                // 成功
                if (state == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
            }

            if (delete) {
                log.info("离线下载完成, 自动删除已完成任务");
                taskDelete(tid);
            }

            List<OpenListFileInfo> openListFileInfos = findFiles(path);

            // 取大小最大的一个视频文件
            Optional<OpenListFileInfo> videoFileOpt = openListFileInfos.stream()
                    .filter(openListFileInfo ->
                            FileUtils.isVideoFormat(openListFileInfo.getName()))
                    .findFirst();

            if (videoFileOpt.isEmpty()) {
                return false;
            }
            OpenListFileInfo videoFile = videoFileOpt.get();
            List<OpenListFileInfo> subtitleList = openListFileInfos.stream()
                    .filter(openListFileInfo ->
                            FileUtils.isSubtitleFormat(openListFileInfo.getName()))
                    .toList();

            Map<String, String> renameMap = new HashMap<>();
            renameMap.put(videoFile.getName(), reName + "." + FileUtil.extName(videoFile.getName()));
            for (OpenListFileInfo openListFileInfo : subtitleList) {
                String name = openListFileInfo.getName();
                String extName = FileUtil.extName(name);
                String newName = reName;
                String lang = FileUtil.extName(FileUtil.mainName(name));
                if (StrUtil.isNotBlank(lang)) {
                    newName = newName + "." + lang;
                }
                renameMap.put(name, newName + "." + extName);
            }

            Boolean rename = config.getRename();

            if (rename) {
                // 重命名
                List<Map<String, String>> renameObjects = renameMap.entrySet().stream()
                        .map(map -> {
                            String srcName = map.getKey();
                            String newName = map.getValue();
                            log.info("重命名 {} ==> {}", srcName, newName);
                            return Map.of(
                                    "src_name", srcName,
                                    "new_name", newName
                            );
                        }).toList();
                fsBatchRename(renameObjects, videoFile.getPath());
            }

            // 移动
            List<String> names = renameMap.entrySet()
                    .stream()
                    .map(m -> rename ? m.getValue() : m.getKey())
                    .toList();
            fsMove(videoFile.getPath(), savePath, names);

            // 删除残留文件夹
            fsRemove(savePath, List.of(reName));

            NotificationUtil.send(config, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END
            );
            return true;
        } catch (Exception e) {
            log.warn("OpenList 下载流程失败 type:{}", e.getClass().getSimpleName());
        }
        return false;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        return false;
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        return false;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {

    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {

    }

    /**
     * 创建文件夹
     *
     * @param path 路径
     */
    public void mkdir(String path) {
        HttpRequest request = postApi("fs/mkdir")
                .body(GsonStatic.toJson(Map.of("path", path)));
        executeApi(request, "fs/mkdir");
        log.info("创建 OpenList 文件夹");
    }

    /**
     * 移动文件
     *
     * @param srcDir 原目录
     * @param dstDir 目标目录
     * @param names  文件名
     */
    public void fsMove(String srcDir, String dstDir, List<String> names) {
        HttpRequest request = postApi("fs/move")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "dst_dir", dstDir,
                        "names", names
                )));
        executeApi(request, "fs/move");
    }

    /**
     * 删除文件
     *
     * @param dir   目录
     * @param names 文件名
     */
    public void fsRemove(String dir, List<String> names) {
        HttpRequest request = postApi("fs/remove")
                .body(GsonStatic.toJson(Map.of(
                        "dir", dir,
                        "names", names
                )));
        executeApi(request, "fs/remove");
    }

    /**
     * 批量重命名
     *
     * @param mapList 重命名列表
     * @param srcDir  目录
     */
    public void fsBatchRename(List<Map<String, String>> mapList, String srcDir) {
        HttpRequest request = postApi("fs/batch_rename")
                .body(GsonStatic.toJson(Map.of(
                        "src_dir", srcDir,
                        "rename_objects", mapList
                )));
        executeApi(request, "fs/batch_rename");
    }

    /**
     * 添加离线下载
     *
     * @param magnet 磁力链接
     * @param path   离线位置
     * @return tid
     */
    public String fsAddOfflineDownload(String magnet, String path) {
        HttpRequest request = postApi("fs/add_offline_download")
                .body(GsonStatic.toJson(Map.of(
                        "path", path,
                        "urls", List.of(magnet),
                        "tool", config.getProvider(),
                        "delete_policy", "delete_on_upload_succeed"
                )));
        JsonObject jsonObject = executeApi(request, "fs/add_offline_download");
        return jsonObject.getAsJsonObject("data")
                .getAsJsonArray("tasks")
                .get(0).getAsJsonObject()
                .get("id").getAsString();
    }

    /**
     * 文件列表
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> fsList(String path, Boolean refresh) {
        HttpRequest request = postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", 1,
                            "per_page", 0,
                            "refresh", refresh
                    )));
        JsonObject jsonObject = executeApi(request, "fs/list");
        JsonElement data = jsonObject.get("data");
        if (Objects.isNull(data) || data.isJsonNull()) {
            return List.of();
        }
        JsonElement content = data.getAsJsonObject().get("content");
        if (Objects.isNull(content) || content.isJsonNull()) {
            return List.of();
        }
        List<OpenListFileInfo> infos = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
        for (OpenListFileInfo info : infos) {
            info.setPath(path);
        }
        return ListUtil.sort(new ArrayList<>(infos), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
    }

    /**
     * 查看任务
     *
     * @param tid 任务id
     * @return 任务信息
     */
    public Optional<OpenListTaskInfo> taskInfo(String tid) {
        JsonObject jsonObject = executeApi(
                postApi("task/offline_download/info").form("tid", tid),
                "task/offline_download/info");
        JsonElement data = jsonObject.get("data");
        if (data == null || data.isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(GsonStatic.fromJson(data, OpenListTaskInfo.class));
    }

    /**
     * 删除残留任务
     *
     * @param magnet 磁力
     */
    public void deleteResidualTasks(String magnet) {
        List<OpenListTaskInfo> taskDoneList = taskDoneList();
        List<OpenListTaskInfo> taskUnDoneList = taskUnDoneList();

        List<OpenListTaskInfo> tasks = new ArrayList<>();
        tasks.addAll(taskDoneList);
        tasks.addAll(taskUnDoneList);

        for (OpenListTaskInfo task : tasks) {
            String id = task.getId();
            String name = task.getName();
            if (name.contains(magnet)) {
                log.info("删除残留任务: {} {}", id, name);
                taskDelete(id);
            }
        }
    }

    /**
     * 未完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskUnDoneList() {
        JsonObject response = executeApi(getApi("task/offline_download/undone"),
                "task/offline_download/undone");
        return taskList(response);
    }

    /**
     * 已完成的离线任务
     *
     * @return 任务列表
     */
    public List<OpenListTaskInfo> taskDoneList() {
        JsonObject response = executeApi(getApi("task/offline_download/done"),
                "task/offline_download/done");
        return taskList(response);
    }

    /**
     * 重试任务
     *
     * @param tid 任务id
     */
    public void taskRetry(String tid) {
        executeApi(postApi("task/offline_download/retry").form("tid", tid),
                "task/offline_download/retry");
    }

    /**
     * 删除任务
     *
     * @param tid 任务id
     */
    public void taskDelete(String tid) {
        executeApi(postApi("task/offline_download/delete_some")
                .body(GsonStatic.toJson(List.of(tid))), "task/offline_download/delete_some");
    }

    /**
     * 获取目录下及子目录的文件
     *
     * @param path 目录
     * @return 文件列表
     */
    public List<OpenListFileInfo> findFiles(String path) {
        List<OpenListFileInfo> openListFileInfos = fsList(path, true);
        List<OpenListFileInfo> list = openListFileInfos.stream()
                .flatMap(openListFileInfo -> {
                    if (openListFileInfo.getIsDir()) {
                        return findFiles(path + "/" + openListFileInfo.getName()).stream();
                    }
                    return Stream.of(openListFileInfo);
                }).toList();

        return ListUtil.sort(new ArrayList<>(list), Comparator.comparing(fileInfo -> {
            Long size = fileInfo.getSize();
            return Long.MAX_VALUE - ObjectUtil.defaultIfNull(size, 0L);
        }));
    }

    /**
     * get api
     *
     * @param action Action
     * @return HttpRequest
     */
    public HttpRequest getApi(String action) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.get(host + "/api/" + action, config)
                .header(Header.AUTHORIZATION, password);
    }

    /**
     * post api
     *
     * @param action Action
     * @return HttpRequest
     */
    public HttpRequest postApi(String action) {
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        return HttpReq.post(host + "/api/" + action, config)
                .header(Header.AUTHORIZATION, password);
    }

    private JsonObject executeApi(HttpRequest request, String action) {
        try (HttpResponse response = request.execute()) {
            int status = response.getStatus();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("OpenList " + action + " HTTP " + status);
            }
            JsonObject json = GsonStatic.fromJson(response.body(), JsonObject.class);
            if (json == null || !json.has("code") || json.get("code").getAsInt() != 200) {
                int code = json != null && json.has("code") ? json.get("code").getAsInt() : -1;
                throw new IllegalStateException("OpenList " + action + " code " + code);
            }
            return json;
        }
    }

    private static List<OpenListTaskInfo> taskList(JsonObject response) {
        JsonElement data = response.get("data");
        if (data == null || data.isJsonNull()) {
            return List.of();
        }
        return GsonStatic.fromJsonList(data.getAsJsonArray(), OpenListTaskInfo.class);
    }

}
