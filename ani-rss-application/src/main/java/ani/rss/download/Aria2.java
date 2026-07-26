package ani.rss.download;

import ani.rss.commons.FileUtils;
import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.Aria2RpcBody;
import ani.rss.entity.torrent.Aria2TorrentsInfo;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.ownership.OwnershipService;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.RenameCacheUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.http.HttpRequest;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;

/**
 * Aria2
 */
@Slf4j
public class Aria2 implements BaseDownload {
    private volatile Config config;

    public Aria2() {
    }

    public Aria2(Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.config = ani.rss.util.other.ConfigUtil.copy(config);
        String host = config.getDownloadToolHost();
        String password = config.getDownloadToolPassword();

        if (StrUtil.isBlank(host) || StrUtil.isBlank(password)) {
            log.warn("Aria2 未配置完成");
            return false;
        }

        Aria2RpcBody aria2RpcBody = Aria2RpcBody.getGlobalStat();

        List<Object> params = aria2RpcBody.getParams();
        params.remove(0);
        params.add("token:" + password);

        return rpcSuccess(aria2RpcBody, this.config);
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        List<TorrentsInfo> torrentsInfos = new ArrayList<>();
        ThreadUtil.sleep(1000);
        try {
            torrentsInfos.addAll(getTorrentsInfos(Aria2RpcBody.tellActive()));
            torrentsInfos.addAll(getTorrentsInfos(Aria2RpcBody.tellWaiting()));
            torrentsInfos.addAll(getTorrentsInfos(Aria2RpcBody.tellStopped()));
        } catch (Exception e) {
            log.warn("读取 Aria2 任务失败 type:{}", e.getClass().getSimpleName());
        }
        return ownedTasks(torrentsInfos);
    }

    public List<TorrentsInfo> getTorrentsInfos(Aria2RpcBody aria2RpcBody) {
        return rpc(aria2RpcBody, configuration())
                .thenFunction(res -> {
                    HttpReq.assertStatus(res);
                    Aria2TorrentsInfo aria2TorrentsInfo = GsonStatic.fromJson(res.body(), Aria2TorrentsInfo.class);

                    List<Aria2TorrentsInfo.Torrent> result = aria2TorrentsInfo.getResult();
                    if (result == null) {
                        return List.of();
                    }

                    return result
                            .stream()
                            .filter(torrent -> {
                                Aria2TorrentsInfo.Bittorrent bittorrent = torrent.getBittorrent();
                                if (Objects.isNull(bittorrent)) {
                                    return false;
                                }
                                Aria2TorrentsInfo.Bittorrent.Info info = bittorrent.getInfo();
                                if (Objects.isNull(info)) {
                                    return false;
                                }
                                String name = info.getName();
                                return StrUtil.isNotBlank(name);
                            })
                            .map(Aria2TorrentsInfo.Torrent::toTorrentsInfo)
                            .toList();
                });
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        String name = item.getReName();

        String extName = FileUtil.extName(torrentFile);
        if (StrUtil.isBlank(extName)) {
            return false;
        }

        if ("txt".equals(extName)) {
            log.error("Aria2 暂不支持磁力链接下载与重命名");
            return false;
        }

        Aria2RpcBody aria2RpcBody = Aria2RpcBody.addTorrent(torrentFile, savePath);

        String id = rpc(aria2RpcBody, configuration())
                .thenFunction(res ->
                        GsonStatic.fromJson(res.body(), JsonObject.class)
                                .get("result").getAsString()
                );

        log.info("aria2 添加下载 => name: {} id: {}", name, id);

        Boolean ova = ani.getOva();
        if (!ova) {
            RenameCacheUtil.put(id, name);
        }

        for (int i = 0; i < 3; i++) {
            ThreadUtil.sleep(1000 * 10);
            List<TorrentsInfo> torrentsInfos = getTorrentsInfos();
            for (TorrentsInfo torrentsInfo : torrentsInfos) {
                if (!torrentsInfo.getId().equals(id)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        String id = torrentsInfo.getId();

        Aria2RpcBody aria2RpcBody = torrentsInfo.getState() == TorrentsStateEnum.stoppedUP
                ? Aria2RpcBody.removeDownloadResult(id)
                : Aria2RpcBody.remove(id);

        try {
            return rpcSuccess(aria2RpcBody, configuration());
        } catch (Exception e) {
            log.warn("删除 Aria2 任务失败 type:{}", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String id = torrentsInfo.getId();
        String savePath = torrentsInfo.getSavePath();
        TorrentsStateEnum torrentsState = torrentsInfo.getState();

        // 仅支持下载完成后重命名
        if (torrentsState != TorrentsStateEnum.stoppedUP) {
            return false;
        }

        String reName = RenameCacheUtil.get(id);
        if (StrUtil.isBlank(reName)) {
            log.debug("未获取到重命名 => id: {}", id);
            return false;
        }

        List<File> files = torrentsInfo.getFilesSupplier().get()
                .stream()
                .map(File::new)
                .filter(File::exists)
                .filter(file -> {
                    String extName = FileUtil.extName(file);
                    if (StrUtil.isBlank(extName)) {
                        return false;
                    }
                    if (file.length() < 1) {
                        return false;
                    }
                    return FileUtils.isVideoFormat(extName) || FileUtils.isSubtitleFormat(extName);
                })
                .sorted(Comparator.comparingLong(file -> Long.MAX_VALUE - file.length()))
                .toList();

        Assert.notEmpty(files, "映射路径存在错误, 无法重命名");

        for (File src : files) {
            String name = src.getName();
            String fileReName = getFileReName(name, reName);
            File newPath = new File(savePath, fileReName);
            if (FileUtil.equals(src, newPath)) {
                continue;
            }
            FileUtil.move(src, newPath, true);
            log.info("重命名 {} ==> {}", name, newPath);
        }
        RenameCacheUtil.remove(id);

        return true;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tags) {
        return false;
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        String trackersStr = CollUtil.join(trackers, ", ");

        Aria2RpcBody aria2RpcBody = Aria2RpcBody.changeGlobalOption(trackersStr);

        Assert.isTrue(rpcSuccess(aria2RpcBody, configuration()), "Aria2 更新 Trackers 失败");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        // api 不支持
    }

    /**
     * rpc请求
     *
     * @param aria2RpcBody 请求体
     * @return HttpRequest
     */
    private HttpRequest rpc(Aria2RpcBody aria2RpcBody, Config config) {
        aria2RpcBody.setId(StrUtil.blankToDefault(config.getUuid(), UUID.randomUUID().toString()));
        List<Object> params = aria2RpcBody.getParams();
        String token = "token:" + StrUtil.blankToDefault(config.getDownloadToolPassword(), "");
        if (params.isEmpty()) {
            params.add(token);
        } else {
            params.set(0, token);
        }
        String host = config.getDownloadToolHost();
        return HttpReq.post(host + "/jsonrpc", config)
                .body(GsonStatic.toJson(aria2RpcBody));
    }

    private boolean rpcSuccess(Aria2RpcBody body, Config config) {
        return rpc(body, config).thenFunction(response -> {
            HttpReq.assertStatus(response);
            JsonObject json = GsonStatic.fromJson(response.body(), JsonObject.class);
            return !json.has("error") && json.has("result");
        });
    }

    private List<TorrentsInfo> ownedTasks(List<TorrentsInfo> tasks) {
        try {
            OwnershipService ownershipService = SpringUtil.getBean(OwnershipService.class);
            return tasks.stream().filter(task -> ownershipService.findManaged(task).isPresent()).toList();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Config configuration() {
        Config configured = config;
        return configured == null ? ConfigUtil.copy(ConfigUtil.CONFIG) : configured;
    }
}
