package ani.rss.notification;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.OpenListFileInfo;
import ani.rss.entity.web.Header;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.QuarantineService;
import ani.rss.service.DownloadService;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpConfig;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.*;

@Slf4j
public class OpenListUploadNotification implements BaseNotification {
    /**
     * 上传配置
     */
    private final HttpConfig httpConfig = new HttpConfig()
            .setBlockSize(8192);

    private NotificationConfig notificationConfig;

    /**
     * 测试
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     */
    @Override
    public void test(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        List<NotificationStatusEnum> statusList = notificationConfig.getStatusList();
        Assert.isTrue(statusList.contains(NotificationStatusEnum.DOWNLOAD_END), "请设置为下载完成通知");

        String openListUploadHost = notificationConfig.getOpenListUploadHost();
        String openListUploadApiKey = notificationConfig.getOpenListUploadApiKey();

        HttpReq.get(openListUploadHost + "/api/me")
                .header(Header.AUTHORIZATION, openListUploadApiKey)
                .then(res -> requireSuccess(res, "me"));
    }

    /**
     * 发送通知
     *
     * @param notificationConfig     通知配置
     * @param ani                    订阅
     * @param text                   通知内容
     * @param notificationStatusEnum 通知状态
     * @return 是否成功
     */
    @Override
    public Boolean send(NotificationConfig notificationConfig, Ani ani, String text, NotificationStatusEnum notificationStatusEnum) {
        if (NotificationStatusEnum.DOWNLOAD_END != notificationStatusEnum) {
            log.info("OpenListUpload 仅支持下载完成通知");
            return true;
        }

        ani = ObjectUtil.clone(ani);

        this.notificationConfig = notificationConfig;

        String openListUploadPath = notificationConfig.getOpenListUploadPath();
        String openListUploadOvaPath = notificationConfig.getOpenListUploadOvaPath();
        Boolean deleteOldEpisode = notificationConfig.getOpenListUploadDeleteOldEpisode();

        DownloadService downloadService = SpringUtil.getBean(DownloadService.class);
        OwnershipService ownershipService = SpringUtil.getBean(OwnershipService.class);

        // 新的位置; 设置自定义下载位置同时启用, 用以获取新的位置
        Boolean ova = ani.getOva();
        String targetTemplate = ova ? openListUploadOvaPath : openListUploadPath;

        Boolean customUploadEnable = ani.getCustomUploadEnable();
        if (customUploadEnable) {
            // 自定义上传位置
            targetTemplate = ani.getCustomUploadPathTarget();
        }

        String target = downloadService.getDownloadPath(ani, targetTemplate);
        target = target.replaceFirst("^[A-Za-z]:", "");

        if (deleteOldEpisode) {
            log.warn("已忽略无法证明远端归属的 OpenList 洗版设置 subscriptionId:{}", ani.getId());
        }

        List<OwnershipService.VerifiedOwnedFile> files = ownershipService.verifiedSubscriptionFiles(ani.getId());
        uploadOwnedFiles(files, target);

        if (Boolean.TRUE.equals(notificationConfig.getOpenListUploadDeleteLocalFile()) && !files.isEmpty()) {
            QuarantineService quarantineService = SpringUtil.getBean(QuarantineService.class);
            String operationId = quarantineService.quarantineSubscription(ani.getId());
            log.info("OpenList 上传后隔离本地归属文件 operationId:{}", operationId);
        }

        return true;
    }

    private void uploadOwnedFiles(List<OwnershipService.VerifiedOwnedFile> ownedFiles, String cloudFilePath) {
        Map<String, OwnershipService.VerifiedOwnedFile> candidates = new LinkedHashMap<>();
        for (OwnershipService.VerifiedOwnedFile ownedFile : ownedFiles) {
            Path fileName = ownedFile.path().getFileName();
            if (fileName == null) {
                throw new IllegalStateException("owned OpenList upload path has no file name");
            }
            String name = fileName.toString();
            OwnershipService.VerifiedOwnedFile collision = candidates.putIfAbsent(name, ownedFile);
            if (collision != null && !collision.path().equals(ownedFile.path())) {
                throw new IllegalStateException("multiple owned files map to the same OpenList target");
            }
        }

        Set<String> remoteNames = new HashSet<>();
        for (OpenListFileInfo file : fileList(cloudFilePath)) {
            if (!remoteNames.add(file.getName())) {
                throw new IllegalStateException("OpenList returned duplicate target names");
            }
        }
        for (String name : candidates.keySet()) {
            if (remoteNames.contains(name)) {
                throw new IllegalStateException("OpenList target already exists; overwrite was refused: " + name);
            }
        }

        for (Map.Entry<String, OwnershipService.VerifiedOwnedFile> entry : candidates.entrySet()) {
            OwnershipService.VerifiedOwnedFile ownedFile = entry.getValue();
            revalidateOwnedFile(ownedFile);
            log.info("OpenList 上传归属文件 name:{}", entry.getKey());
            uploadFile(ownedFile.path(), cloudFilePath, entry.getKey());
        }
    }

    private static void revalidateOwnedFile(OwnershipService.VerifiedOwnedFile ownedFile) {
        try {
            Path path = ownedFile.path();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                    Files.size(path) != ownedFile.size() ||
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() != ownedFile.lastModified()) {
                throw new IllegalStateException("owned file changed before OpenList upload");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("revalidate owned file failed", e);
        }
    }

    private void uploadFile(Path localFile, String cloudFilePath, String filename) {
        String openListUploadHost = notificationConfig.getOpenListUploadHost();
        String openListUploadApiKey = notificationConfig.getOpenListUploadApiKey();
        String url = StrUtil.format("{}/api/fs/put", openListUploadHost);

        HttpReq.put(url)
                .timeout(1000 * 60 * 2)
                .setConfig(httpConfig)
                .header(Header.AUTHORIZATION, openListUploadApiKey)
                .header("As-Task", "false")
                .header("File-Path", URLUtil.encode(cloudFilePath + "/" + filename))
                .contentType("application/octet-stream")
                .body(ResourceUtil.getResourceObj(localFile.toString()))
                .then(res -> {
                    requireSuccess(res, "fs/put");
                    log.info("OpenList 上传完成 name:{}", filename);
                });
    }

    public List<OpenListFileInfo> fileList(String path) {
        final int pageSize = 1000;
        final int maxEntries = 50_000;
        List<OpenListFileInfo> result = new ArrayList<>();
        for (int page = 1; result.size() < maxEntries; page++) {
            int currentPage = page;
            RemotePage remotePage = postApi("fs/list")
                    .body(GsonStatic.toJson(Map.of(
                            "path", path,
                            "page", currentPage,
                            "per_page", pageSize,
                            "refresh", currentPage == 1
                    )))
                    .thenFunction(res -> parseRemotePage(res, path));
            result.addAll(remotePage.files());
            if (result.size() > maxEntries) {
                throw new IllegalStateException("OpenList directory exceeds safe listing limit");
            }
            if (remotePage.files().size() < pageSize ||
                    (remotePage.total() >= 0 && result.size() >= remotePage.total())) {
                return List.copyOf(result);
            }
        }
        throw new IllegalStateException("OpenList directory exceeds safe listing limit");
    }

    private static RemotePage parseRemotePage(HttpResponse response, String path) {
        JsonObject root = requireSuccess(response, "fs/list");
        JsonElement dataElement = root.get("data");
        if (dataElement == null || dataElement.isJsonNull() || !dataElement.isJsonObject()) {
            throw new IllegalStateException("OpenList fs/list response has no data object");
        }
        JsonObject data = dataElement.getAsJsonObject();
        JsonElement content = data.get("content");
        if (content == null || content.isJsonNull()) {
            return new RemotePage(List.of(), number(data, "total", 0));
        }
        if (!content.isJsonArray()) {
            throw new IllegalStateException("OpenList fs/list response has invalid content");
        }
        List<OpenListFileInfo> files = GsonStatic.fromJsonList(content.getAsJsonArray(), OpenListFileInfo.class);
        for (OpenListFileInfo file : files) {
            file.setPath(path);
        }
        return new RemotePage(List.copyOf(files), number(data, "total", -1));
    }

    private static JsonObject requireSuccess(HttpResponse response, String action) {
        if (response == null || !response.isOk()) {
            int status = response == null ? 0 : response.getStatus();
            throw new IllegalStateException("OpenList " + action + " HTTP failure: " + status);
        }
        JsonObject root;
        try {
            root = GsonStatic.fromJson(response.body(), JsonObject.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("OpenList " + action + " returned invalid JSON", e);
        }
        if (root == null || root.get("code") == null || !root.get("code").isJsonPrimitive()) {
            throw new IllegalStateException("OpenList " + action + " response has no code");
        }
        int code = root.get("code").getAsInt();
        if (code != 200) {
            throw new IllegalStateException("OpenList " + action + " rejected request with code " + code);
        }
        return root;
    }

    private static long number(JsonObject object, String name, long fallback) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? fallback : element.getAsLong();
    }

    private record RemotePage(List<OpenListFileInfo> files, long total) {
    }

    /**
     * post api
     *
     * @param action 操作
     * @return HttpReq
     */
    public HttpRequest postApi(String action) {
        String openListUploadHost = notificationConfig.getOpenListUploadHost();
        String openListUploadApiKey = notificationConfig.getOpenListUploadApiKey();
        return HttpReq.post(openListUploadHost + "/api/" + action)
                .header(Header.AUTHORIZATION, openListUploadApiKey);
    }
}
