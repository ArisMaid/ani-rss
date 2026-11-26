package ani.rss.notification;

import ani.rss.commons.GsonStatic;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.Ani;
import ani.rss.entity.NotificationConfig;
import ani.rss.entity.web.ContentType;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class AstrBotNotification implements BaseNotification {
    @Override
    public void test(NotificationConfig config, Ani ani, String text, NotificationStatusEnum status) {
        send(config, ani, text, status);
    }

    @Override
    public Boolean send(NotificationConfig config, Ani ani, String text, NotificationStatusEnum status) {
        String host = StrUtil.trimToEmpty(config.getAstrBotHost()).replaceFirst("/+$", "");
        String apiKey = StrUtil.trim(config.getAstrBotApiKey());
        String umo = StrUtil.trim(config.getAstrBotUmo());
        Assert.notBlank(host, "请设置 AstrBot 服务地址");
        Assert.notBlank(apiKey, "请设置 AstrBot API Key");
        Assert.notBlank(umo, "请设置 AstrBot 目标会话 UMO");

        String message = replaceNotificationTemplate(ani, config, text, status);
        Assert.notBlank(message, "AstrBot 通知内容不能为空，请检查通知模板");

        Object content = message;
        if (Boolean.TRUE.equals(config.getAstrBotImage()) && StrUtil.isNotBlank(ani.getCover())) {
            File photo = findCover(ani.getCover());
            if (photo != null) {
                String attachmentId = uploadCover(host, apiKey, photo);
                content = List.of(
                        Map.of("type", "image", "attachment_id", attachmentId),
                        Map.of("type", "plain", "text", message));
            }
        }

        return HttpReq.post(host + "/api/v1/im/message", Map.of("umo", umo, "message", content))
                .header("Authorization", "Bearer " + apiKey)
                .contentType(ContentType.JSON)
                .setFollowRedirects(false)
                .thenFunction(response -> {
                    JsonObject result = readResponse(response, "发送消息");
                    String responseStatus = requiredString(result, "status", "发送消息");
                    Assert.isTrue("ok".equalsIgnoreCase(responseStatus), "AstrBot 发送消息失败，响应状态不是 ok");
                    return true;
                });
    }

    private static File findCover(String cover) {
        Path configRoot = ConfigUtil.getConfigDir().toPath();
        Path filesRoot = configRoot.resolve("files");
        Path photo = PathPolicy.resolveWithin(filesRoot, cover);
        PathPolicy.requireNoSymbolicLinks(configRoot, photo);
        if (!Files.isRegularFile(photo, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            PathPolicy.realPathWithin(configRoot, filesRoot);
            return PathPolicy.realPathWithin(filesRoot, photo).toFile();
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取 AstrBot 通知封面", e);
        }
    }

    private static String uploadCover(String host, String apiKey, File photo) {
        return HttpReq.post(host + "/api/v1/file")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(ContentType.MULTIPART)
                .setFollowRedirects(false)
                .form("file", photo)
                .thenFunction(response -> {
                    JsonObject result = readResponse(response, "上传封面");
                    JsonElement data = result.get("data");
                    Assert.isTrue(data != null && data.isJsonObject(), "AstrBot 上传封面响应缺少 data");
                    return requiredString(data.getAsJsonObject(), "attachment_id", "上传封面");
                });
    }

    private static JsonObject readResponse(HttpResponse response, String action) {
        HttpReq.assertStatus(response);
        try {
            return Assert.notNull(GsonStatic.fromJson(response.body(), JsonObject.class),
                    "AstrBot {}响应为空", action);
        } catch (JsonParseException e) {
            throw new IllegalStateException("AstrBot " + action + "响应 JSON 无效", e);
        }
    }

    private static String requiredString(JsonObject object, String name, String action) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalStateException("AstrBot " + action + "响应缺少有效的 " + name);
        }
        return Assert.notBlank(value.getAsString(), "AstrBot {}响应的 {} 为空", action, name);
    }
}
