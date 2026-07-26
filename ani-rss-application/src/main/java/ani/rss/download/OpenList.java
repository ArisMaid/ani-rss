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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Slf4j
public class OpenList implements BaseDownload {
    private static final long POLL_INTERVAL_MILLIS = 1_000L;
    private Config config;
    private final ConcurrentMap<String, Workflow> workflows = new ConcurrentHashMap<>();
    private final ThreadLocal<String> submittedTaskId = new ThreadLocal<>();

    public OpenList() {
    }

    public OpenList(Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        return connectResult(test, config).isSuccess();
    }

    @Override
    public DownloaderResult<Void> connectResult(Boolean test, Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();
        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("OpenList 未配置完成");
            return DownloaderResult.rejected("OPENLIST_CONFIGURATION_INCOMPLETE");
        }
        String downloadPath = config.getDownloadPathTemplate();
        if (StrUtil.isBlank(downloadPath)) {
            return DownloaderResult.rejected("OPENLIST_DOWNLOAD_PATH_MISSING");
        }
        String provider = config.getProvider();
        if (StrUtil.isBlank(provider)) {
            return DownloaderResult.rejected("OPENLIST_PROVIDER_MISSING");
        }
        try {
            executeApi(getApi("me"), "login");
            return DownloaderResult.success(null);
        } catch (Exception e) {
            log.warn("登录 OpenList 失败 type:{}", e.getClass().getSimpleName());
            return DownloaderFailures.result(e);
        }
    }


    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return List.of();
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        return downloadResult(ani, item, savePath, torrentFile).isSuccess();
    }

    @Override
    public DownloaderResult<Void> downloadResult(Ani ani, Item item, String savePath, File torrentFile) {
        String workflowKey = StrUtil.blankToDefault(item.getInfoHash(), TorrentUtil.getMagnet(torrentFile));
        Workflow workflow = workflows.computeIfAbsent(workflowKey, ignored -> new Workflow());
        synchronized (workflow) {
            submittedTaskId.remove();
            try {
                boolean success = downloadInternal(ani, item, savePath, torrentFile, workflow);
                String remoteTaskId = submittedTaskId.get();
                if (success) {
                    workflows.remove(workflowKey, workflow);
                    return DownloaderResult.success(null, remoteTaskId);
                }
                return DownloaderResult.rejected("OPENLIST_DOWNLOAD_REJECTED", remoteTaskId);
            } catch (Exception e) {
                return DownloaderFailures.result(e, submittedTaskId.get());
            } finally {
                submittedTaskId.remove();
            }
        }
    }

    private Boolean downloadInternal(
            Ani ani, Item item, String savePath, File torrentFile, Workflow workflow) {
        // windows 真该死啊
        savePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");

        String magnet = TorrentUtil.getMagnet(torrentFile);
        String reName = item.getReName();
        String path = savePath + "/" + reName;
        Boolean delete = config.getDelete();
        try {
            ensureDirectory(path, workflow);
            String tid = workflow.taskId;
            try {
                if (StrUtil.isBlank(tid)) {
                    tid = findExistingTaskId(magnet).orElseGet(() -> fsAddOfflineDownload(magnet, path));
                    workflow.taskId = tid;
                    workflow.startedAtMillis = System.currentTimeMillis();
                }
                submittedTaskId.set(tid);
                log.info("添加离线下载成功 {}", reName);
            } catch (Exception e) {
                log.error("添加离线下载失败 {}", reName);
                throw new IllegalStateException("OpenList task submission failed", e);
            }

            if (!workflow.taskSucceeded) {
                if (workflow.startedAtMillis == 0L) {
                    workflow.startedAtMillis = System.currentTimeMillis();
                }

                // 重试次数
                long retry = 0;
                while (true) {
                Integer openListDownloadTimeout = config.getOpenListDownloadTimeout();
                Long openListDownloadRetryNumber = config.getOpenListDownloadRetryNumber();

                long timeoutMillis = TimeUnit.MINUTES.toMillis(openListDownloadTimeout);
                if (System.currentTimeMillis() - workflow.startedAtMillis >= timeoutMillis) {
                    // 超过下载超时限制
                    log.error("{} {} 分钟还未下载完成, 停止检测下载", reName, openListDownloadTimeout);
                    throw DownloaderOperationException.failed("OPENLIST_TASK_TIMEOUT", false);
                }

                Optional<OpenListTaskInfo> taskInfoOpt = taskInfo(tid);

                if (taskInfoOpt.isEmpty()) {
                    sleepPoll();
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
                            throw DownloaderOperationException.rejected("OPENLIST_TASK_FAILED");
                        }
                        retry++;
                        log.info("离线任务正在进行重试 {}, 当前重试次数 {}, 最大重试次数 {}", tid, retry, openListDownloadRetryNumber);
                    }
                    taskRetry(tid);
                    sleepPoll();
                    continue;
                }

                if (
                        List.of(
                                OpenListTaskInfo.State.Canceling,
                                OpenListTaskInfo.State.Canceled
                        ).contains(state)
                ) {
                    log.error("离线任务已被取消 {}", reName);
                    throw DownloaderOperationException.rejected("OPENLIST_TASK_CANCELED");
                }

                // 成功
                if (state == OpenListTaskInfo.State.Succeeded) {
                    break;
                }
                    sleepPoll();
                }
                workflow.taskSucceeded = true;
            }

            if (workflow.files == null) {
                workflow.files = buildFilePlans(path, reName, Boolean.TRUE.equals(config.getRename()));
            }
            applyRenames(workflow.files);
            moveFiles(workflow.files, savePath);

            if (delete && !workflow.taskDeleted) {
                log.info("离线下载完成, 自动删除已完成任务记录");
                if (taskInfo(tid).isPresent()) {
                    taskDelete(tid);
                }
                workflow.taskDeleted = true;
            }

            NotificationUtil.send(config, ani,
                    StrFormatter.format("{} 下载完成", item.getReName()),
                    NotificationStatusEnum.DOWNLOAD_END
            );
            return true;
        } catch (RuntimeException e) {
            log.warn("OpenList 下载流程失败 type:{}", e.getClass().getSimpleName());
            throw e;
        }
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

    @Override
    public DownloaderResult<Void> recoverResult(TorrentsInfo torrentsInfo) {
        // OpenList does not expose a safe local recheck. Its idempotent
        // download workflow is reused only after the recovery service has
        // verified that the expected target is absent.
        return DownloaderResult.rejected("OPENLIST_RECOVERY_REQUEUE_REQUIRED");
    }

    private void ensureDirectory(String path, Workflow workflow) {
        if (workflow.directoryReady) {
            return;
        }
        try {
            mkdir(path);
        } catch (DownloaderOperationException e) {
            if (!"OPENLIST_APPLICATION_REJECTED".equals(e.errorCode())) {
                throw e;
            }
            // mkdir may report an application error when the directory already exists.
            fsList(path, false);
        }
        workflow.directoryReady = true;
    }

    private List<FilePlan> buildFilePlans(String sourceRoot, String reName, boolean rename) {
        List<OpenListFileInfo> files = findFiles(sourceRoot);
        OpenListFileInfo video = files.stream()
                .filter(file -> FileUtils.isVideoFormat(file.getName()))
                .findFirst()
                .orElseThrow(() -> DownloaderOperationException.failed(
                        "OPENLIST_VIDEO_NOT_FOUND", false));

        List<OpenListFileInfo> selected = new ArrayList<>();
        selected.add(video);
        files.stream()
                .filter(file -> FileUtils.isSubtitleFormat(file.getName()))
                .forEach(selected::add);

        Set<String> targets = new HashSet<>();
        List<FilePlan> plans = new ArrayList<>();
        for (OpenListFileInfo file : selected) {
            String targetName = file.getName();
            if (rename && FileUtils.isVideoFormat(file.getName())) {
                targetName = reName + "." + FileUtil.extName(file.getName());
            } else if (rename && FileUtils.isSubtitleFormat(file.getName())) {
                String language = FileUtil.extName(FileUtil.mainName(file.getName()));
                targetName = reName + (StrUtil.isBlank(language) ? "" : "." + language) +
                        "." + FileUtil.extName(file.getName());
            }
            if (!targets.add(targetName)) {
                throw DownloaderOperationException.rejected("OPENLIST_TARGET_CONFLICT");
            }
            plans.add(new FilePlan(file.getPath(), file.getName(), targetName));
        }
        return List.copyOf(plans);
    }

    private void applyRenames(List<FilePlan> plans) {
        Map<String, List<FilePlan>> byDirectory = plans.stream()
                .filter(plan -> !plan.sourceName().equals(plan.targetName()))
                .collect(java.util.stream.Collectors.groupingBy(
                        FilePlan::sourceDirectory, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (Map.Entry<String, List<FilePlan>> entry : byDirectory.entrySet()) {
            Set<String> currentNames = fileNames(entry.getKey());
            List<Map<String, String>> pending = new ArrayList<>();
            for (FilePlan plan : entry.getValue()) {
                boolean sourceExists = currentNames.contains(plan.sourceName());
                boolean targetExists = currentNames.contains(plan.targetName());
                if (sourceExists && targetExists) {
                    throw DownloaderOperationException.rejected("OPENLIST_TARGET_CONFLICT");
                }
                if (!sourceExists && targetExists) {
                    continue;
                }
                if (!sourceExists) {
                    throw DownloaderOperationException.failed("OPENLIST_SOURCE_MISSING", false);
                }
                log.info("重命名 {} ==> {}", plan.sourceName(), plan.targetName());
                pending.add(Map.of("src_name", plan.sourceName(), "new_name", plan.targetName()));
            }
            if (!pending.isEmpty()) {
                fsBatchRename(pending, entry.getKey());
            }
        }
    }

    private void moveFiles(List<FilePlan> plans, String destinationDirectory) {
        String normalizedDestination = normalizeRemotePath(destinationDirectory);
        Set<String> destinationNames = fileNames(destinationDirectory);
        Map<String, List<FilePlan>> byDirectory = plans.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        FilePlan::sourceDirectory, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        for (Map.Entry<String, List<FilePlan>> entry : byDirectory.entrySet()) {
            if (normalizeRemotePath(entry.getKey()).equals(normalizedDestination)) {
                continue;
            }
            Set<String> sourceNames = fileNames(entry.getKey());
            List<String> pending = new ArrayList<>();
            for (FilePlan plan : entry.getValue()) {
                boolean sourceExists = sourceNames.contains(plan.targetName());
                boolean targetExists = destinationNames.contains(plan.targetName());
                if (sourceExists && targetExists) {
                    throw DownloaderOperationException.rejected("OPENLIST_TARGET_CONFLICT");
                }
                if (!sourceExists && targetExists) {
                    continue;
                }
                if (!sourceExists) {
                    throw DownloaderOperationException.failed("OPENLIST_SOURCE_MISSING", false);
                }
                pending.add(plan.targetName());
            }
            if (!pending.isEmpty()) {
                fsMove(entry.getKey(), destinationDirectory, pending);
                destinationNames.addAll(pending);
            }
        }
    }

    private Set<String> fileNames(String directory) {
        return fsList(directory, true).stream()
                .map(OpenListFileInfo::getName)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private static String normalizeRemotePath(String path) {
        String normalized = StrUtil.blankToDefault(path, "/").replace('\\', '/').replaceAll("/+", "/");
        return normalized.length() > 1 && normalized.endsWith("/")
                ? normalized.substring(0, normalized.length() - 1)
                : normalized;
    }

    private static void sleepPoll() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw DownloaderOperationException.failed("OPENLIST_INTERRUPTED", false, e);
        }
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

    /**
     * Checks the exact expected media name at an OpenList target. A transport
     * or API error returns empty so recovery will not blindly resubmit work.
     */
    public Optional<Boolean> hasExpectedMedia(String savePath, String expectedName) {
        if (StrUtil.isBlank(savePath) || StrUtil.isBlank(expectedName)) {
            return Optional.empty();
        }
        try {
            String remotePath = ReUtil.replaceAll(savePath, "^[A-z]:", "");
            boolean present = findFiles(remotePath).stream()
                    .filter(file -> FileUtils.isVideoFormat(file.getName()))
                    .anyMatch(file -> expectedName.equalsIgnoreCase(FileUtil.mainName(file.getName())));
            return Optional.of(present);
        } catch (RuntimeException e) {
            log.warn("OpenList 目标验证失败 type:{}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<String> findExistingTaskId(String magnet) {
        String normalizedMagnet = StrUtil.blankToDefault(magnet, "").toLowerCase(Locale.ROOT);
        String infoHash = ReUtil.get("(?i)btih:([a-z0-9]+)", normalizedMagnet, 1);
        return Stream.concat(taskDoneList().stream(), taskUnDoneList().stream())
                .filter(task -> {
                    String name = StrUtil.blankToDefault(task.getName(), "").toLowerCase(Locale.ROOT);
                    return (!normalizedMagnet.isBlank() && name.contains(normalizedMagnet)) ||
                            (StrUtil.isNotBlank(infoHash) && name.contains(infoHash.toLowerCase(Locale.ROOT)));
                })
                .map(OpenListTaskInfo::getId)
                .filter(StrUtil::isNotBlank)
                .findFirst();
    }

    private JsonObject executeApi(HttpRequest request, String action) {
        try (HttpResponse response = request.execute()) {
            int status = response.getStatus();
            if (status < 200 || status >= 300) {
                throw DownloaderOperationException.http("OPENLIST", status);
            }
            JsonObject json = GsonStatic.fromJson(response.body(), JsonObject.class);
            if (json == null || !json.has("code") || json.get("code").getAsInt() != 200) {
                int code = json != null && json.has("code") ? json.get("code").getAsInt() : -1;
                throw DownloaderOperationException.rejected("OPENLIST_APPLICATION_REJECTED");
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

    private record FilePlan(String sourceDirectory, String sourceName, String targetName) {
    }

    private static final class Workflow {
        private String taskId;
        private boolean directoryReady;
        private boolean taskSucceeded;
        private boolean taskDeleted;
        private long startedAtMillis;
        private List<FilePlan> files;
    }

}
