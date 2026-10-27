package ani.rss.mcp;

import ani.rss.commons.GsonStatic;
import ani.rss.controller.AniController;
import ani.rss.entity.*;
import ani.rss.entity.dto.AniBTQueryDTO;
import ani.rss.entity.dto.RssToAniDTO;
import ani.rss.entity.web.Result;
import ani.rss.entity.web.ResultCode;
import ani.rss.exception.ResultException;
import ani.rss.mcp.dto.DeleteSubscriptionDTO;
import ani.rss.mcp.dto.ListSubscriptionDTO;
import ani.rss.mcp.dto.SearchMikanDTO;
import ani.rss.mcp.vo.SubscriptionItemsPreviewVO;
import ani.rss.service.AniBTService;
import ani.rss.service.AnimeGardenService;
import ani.rss.service.MikanService;
import ani.rss.service.SubscriptionDeletionService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ItemsUtil;
import cn.hutool.core.util.ObjectUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Component
public class AniMcpTools {

    @Resource
    private AniController aniController;

    @Resource
    private MikanService mikanService;

    @Resource
    private AniBTService aniBTService;

    @Resource
    private AnimeGardenService animeGardenService;

    @Resource
    private SubscriptionDeletionService subscriptionDeletionService;

    @McpTool(
            name = "list_subscriptions",
            description = "列出现有 ANI-RSS 订阅，可按启用状态过滤",
            annotations = @McpTool.McpAnnotations(
                    title = "订阅列表",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public List<Ani> listSubscriptions(@McpToolParam ListSubscriptionDTO dto) {
        Boolean enabled = dto.getEnabled();
        return AniUtil.ANI_LIST.stream()
                .filter(ani -> Objects.isNull(enabled) || enabled.equals(ani.getEnable()))
                .toList();
    }

    @McpTool(
            name = "search_mikan",
            description = "按关键词搜索 Mikan 番剧，可传入季度条件",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 Mikan",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public Mikan searchMikan(@McpToolParam SearchMikanDTO dto) {
        String text = dto.getText();
        Mikan.Season season = dto.getSeason();
        text = ObjectUtil.defaultIfNull(text, "");
        season = ObjectUtil.defaultIfNull(season, new Mikan.Season());
        return mikanService.list(text, season);
    }

    @McpTool(
            name = "search_anibt",
            description = "搜索 AniBT 番剧",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 AniBT",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public AniBT searchAniBT() {
        return aniBTService.list(new AniBTQueryDTO());
    }

    @McpTool(
            name = "search_anime_garden",
            description = "搜索 AnimeGarden 番剧列表",
            annotations = @McpTool.McpAnnotations(
                    title = "搜索 AnimeGarden",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AnimeGarden.Week> searchAnimeGarden() {
        return animeGardenService.list("");
    }

    @McpTool(
            name = "get_mikan_groups",
            description = "根据 Mikan 番剧页面 URL 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 Mikan 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<Mikan.Group> getMikanGroups(
            @McpToolParam(description = "蜜柑（Mikan）番剧页面 URL", required = true)
            String url
    ) {
        return mikanService.getGroups(url);
    }

    @McpTool(
            name = "get_anibt_groups",
            description = "根据 AniBT BGM ID 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 AniBT 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AniBT.Group> getAniBTGroups(
            @McpToolParam(description = "BGM 番剧 ID", required = true)
            String bgmId
    ) {
        return aniBTService.getGroups(bgmId);
    }

    @McpTool(
            name = "get_anime_garden_groups",
            description = "根据 AnimeGarden BGM ID 获取字幕组 RSS",
            annotations = @McpTool.McpAnnotations(
                    title = "获取 AnimeGarden 字幕组",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public List<AnimeGarden.Group> getAnimeGardenGroups(
            @McpToolParam(description = "BGM 番剧 ID")
            String bgmId
    ) {
        return animeGardenService.group(bgmId);
    }

    @McpTool(
            name = "preview_subscription_items",
            description = "预览某个 RSS 订阅最终会命中的原始剧集条目",
            annotations = @McpTool.McpAnnotations(
                    title = "预览订阅条目",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = true
            )
    )
    public SubscriptionItemsPreviewVO previewSubscriptionItems(@McpToolParam RssToAniDTO dto) {
        try {
            Ani ani = AniUtil.getAni(dto);
            List<Item> items = ItemsUtil.getItems(ani);
            return new SubscriptionItemsPreviewVO(ani, items);
        } catch (Exception e) {
            throw mcpException("RSS解析失败", e);
        }
    }

    @McpTool(
            name = "add_subscription",
            description = "添加一个 ANI-RSS 订阅。需要预览命中条目时请先调用 preview_subscription_items",
            annotations = @McpTool.McpAnnotations(
                    title = "添加订阅",
                    readOnlyHint = false,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public Ani addSubscription(@McpToolParam RssToAniDTO dto) {
        try {
            Ani ani = AniUtil.getAni(dto);
            aniController.addAni(ani);
            return ani;
        } catch (Exception e) {
            throw mcpException("创建订阅失败", e);
        }
    }

    @McpTool(
            name = "edit_subscription",
            description = "编辑已有订阅。先调用 list_subscriptions 取得订阅 ID，再传入 ID 和要修改的字段；未传或为 null 的字段保持原值，清空文本或列表请传空字符串或空数组",
            annotations = @McpTool.McpAnnotations(
                    title = "编辑订阅",
                    destructiveHint = false,
                    idempotentHint = true,
                    openWorldHint = false
            )
    )
    public Ani editSubscription(
            @McpToolParam(description = "订阅字段，id 必须对应现有订阅；可只提供需要修改的字段")
            Map<String, Object> subscription
    ) {
        try {
            Object idValue = subscription == null ? null : subscription.get("id");
            if (!(idValue instanceof String id) || id.isBlank()) {
                throw new IllegalArgumentException("编辑订阅失败：必须提供已有订阅的 ID");
            }
            Ani current = AniUtil.ANI_LIST.stream()
                    .filter(ani -> id.equals(ani.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("编辑订阅失败：订阅不存在"));
            JsonObject changes = GsonStatic.GSON.newBuilder().serializeNulls().create()
                    .toJsonTree(subscription).getAsJsonObject();
            canonicalizeAlias(changes, "downloadPath", "customDownloadPathTemplate");
            canonicalizeAlias(changes, "customAlistPath", "customUploadEnable");
            canonicalizeAlias(changes, "alistPath", "customUploadPathTarget");

            // MCP/Jackson emits UTC timestamps; the stored Gson adapters expect local dates.
            normalizeDate(changes, "releaseDate");
            JsonElement tmdb = changes.get("tmdb");
            if (tmdb != null && tmdb.isJsonObject()) {
                normalizeDate(tmdb.getAsJsonObject(), "date");
            }
            JsonObject merged = GsonStatic.GSON.toJsonTree(current).getAsJsonObject();
            if (tmdb != null && tmdb.isJsonObject() && merged.has("tmdb")
                    && merged.get("tmdb").isJsonObject()) {
                JsonObject mergedTmdb = merged.getAsJsonObject("tmdb");
                mergeNonNull(mergedTmdb, tmdb.getAsJsonObject());
                changes.add("tmdb", mergedTmdb);
            }
            mergeNonNull(merged, changes);
            Ani updated = GsonStatic.fromJson(merged, Ani.class);
            Result<Void> result = aniController.setAniFromMcp(updated);
            if (!Objects.equals(result.getCode(), ResultCode.HTTP_OK)) {
                throw new IllegalStateException("编辑订阅失败：" + result.getMessage());
            }
            return current;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("编辑订阅失败", e);
            throw new IllegalStateException("编辑订阅失败", e);
        }
    }

    @McpTool(
            name = "delete_subscription",
            description = "删除订阅及其已归属的下载器任务；默认保留本地文件，deleteFiles 为 true 时才删除可确认归属的文件",
            annotations = @McpTool.McpAnnotations(
                    title = "删除订阅",
                    readOnlyHint = false,
                    destructiveHint = true,
                    idempotentHint = false,
                    openWorldHint = false
            )
    )
    public List<String> deleteSubscription(@McpToolParam DeleteSubscriptionDTO dto) {
        try {
            if (dto == null || dto.getIds() == null || dto.getIds().isEmpty()
                    || dto.getIds().stream().anyMatch(id -> id == null || id.isBlank())) {
                throw new IllegalArgumentException("删除订阅失败：必须提供订阅 ID");
            }
            List<String> ids = dto.getIds().stream().map(String::trim).distinct().toList();
            subscriptionDeletionService.delete(ids, Boolean.TRUE.equals(dto.getDeleteFiles()));
            return ids;
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("删除订阅失败", e);
            throw new IllegalStateException("删除订阅失败", e);
        }
    }

    private static void canonicalizeAlias(JsonObject changes, String alias, String canonical) {
        JsonElement value = changes.remove(alias);
        if (value != null && !changes.has(canonical)) {
            changes.add(canonical, value);
        }
    }

    private static void mergeNonNull(JsonObject target, JsonObject changes) {
        changes.entrySet().stream().filter(entry -> !entry.getValue().isJsonNull())
                .forEach(entry -> target.add(entry.getKey(), entry.getValue()));
    }

    private static void normalizeDate(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive()) {
            return;
        }
        Date date;
        if (value.getAsJsonPrimitive().isNumber()) {
            date = new Date(value.getAsLong());
        } else {
            try {
                date = Date.from(OffsetDateTime.parse(value.getAsString()).toInstant());
            } catch (DateTimeParseException e) {
                return; // Date-only inputs continue through the existing Gson adapter.
            }
        }
        object.add(field, GsonStatic.GSON.toJsonTree(new Ani().setReleaseDate(date))
                .getAsJsonObject().get("releaseDate"));
    }

    private ResultException mcpException(String action, Exception e) {
        log.error(e.getMessage(), e);
        return ResultException.exception(action);
    }
}
