package ani.rss.download;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.entity.torrent.TransmissionRpcBody;
import ani.rss.entity.torrent.TransmissionTorrentsInfo;
import ani.rss.entity.web.Header;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.basic.RenameCacheUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Transmission RPC client with protocol negotiation for 3.x/4.0 and 4.1.
 */
@Slf4j
public class Transmission implements BaseDownload {
    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong requestIds = new AtomicLong(1);
    /** The last explicitly tested configuration, kept as an immutable snapshot. */
    private volatile Config activeConfig;

    public Transmission() {
    }

    public Transmission(Config config) {
        this.activeConfig = ConfigUtil.copy(config);
    }

    @Override
    public Boolean login(Boolean test, Config config) {
        this.activeConfig = ConfigUtil.copy(config);
        ensureSession(config);
        if (!test) {
            getTorrentsInfos(config);
        }
        return true;
    }

    @Override
    public List<TorrentsInfo> getTorrentsInfos() {
        return getTorrentsInfos(configuration());
    }

    private List<TorrentsInfo> getTorrentsInfos(Config config) {
        SessionState state = ensureSession(config);
        RpcResponse response = rpc(config, TransmissionRpcBody.torrentGet(), state);
        JsonObject payload = TransmissionRpcCodec.payload(response.body(), state.dialect());
        TransmissionTorrentsInfo.Arguments arguments = GsonStatic.fromJson(
                payload.toString(), TransmissionTorrentsInfo.Arguments.class);
        List<TransmissionTorrentsInfo.Torrent> torrents = arguments.getTorrents();
        if (torrents == null) {
            return List.of();
        }
        return torrents.stream()
                .map(TransmissionTorrentsInfo.Torrent::toTorrentsInfo)
                .filter(torrentsInfo -> Optional.ofNullable(torrentsInfo.getTagList())
                        .orElseGet(List::of)
                        .contains(TorrentsTagEnum.ANI_RSS.getValue()))
                .toList();
    }

    public TransmissionRpcBody getTorrentAddBody(Ani ani, Item item, String savePath, File torrentFile) {
        String extName = FileUtil.extName(torrentFile);
        List<String> tags = newTags(ani, item, configuration());

        if ("txt".equals(extName)) {
            String magnet = FileUtil.readUtf8String(torrentFile);
            return TransmissionRpcBody.torrentAdd(tags, magnet, savePath);
        }
        if (torrentFile.length() > 0) {
            return TransmissionRpcBody.torrentAdd(tags, torrentFile, savePath);
        }
        String magnet = "magnet:?xt=urn:btih:" + FileUtil.mainName(torrentFile);
        return TransmissionRpcBody.torrentAdd(tags, magnet, savePath);
    }

    @Override
    public Boolean download(Ani ani, Item item, String savePath, File torrentFile) {
        return downloadResult(ani, item, savePath, torrentFile).isSuccess();
    }

    @Override
    public DownloaderResult<Void> downloadResult(Ani ani, Item item, String savePath, File torrentFile) {
        Config config = configuration();
        SessionState state = ensureSession(config);
        TransmissionRpcBody body = getTorrentAddBody(ani, item, savePath, torrentFile);
        RpcResponse response = rpc(config, body, state);
        JsonObject payload = TransmissionRpcCodec.payload(response.body(), state.dialect());
        JsonObject added = payload.has("torrent-added")
                ? payload.getAsJsonObject("torrent-added")
                : payload.getAsJsonObject("torrent_added");
        String remoteReference = responseReference(added);
        if (StrUtil.isBlank(remoteReference)) {
            throw DownloaderOperationException.failed("TRANSMISSION_INVALID_RESPONSE", false);
        }
        log.info("Transmission 添加下载 name:{} reference:{}", item.getReName(), remoteReference);

        if (!ani.getOva()) {
            RenameCacheUtil.put(remoteReference, item.getReName());
        }

        return DownloaderResult.success(null, remoteReference);
    }

    @Override
    public Boolean delete(TorrentsInfo torrentsInfo, Boolean deleteFiles) {
        Config config = configuration();
        SessionState state = ensureSession(config);
        return TransmissionRpcCodec.success(
                rpc(config,
                        TransmissionRpcBody.torrentRemove(taskReference(torrentsInfo), false), state).body(),
                state.dialect());
    }

    @Override
    public DownloaderResult<Void> recoverResult(TorrentsInfo torrentsInfo) {
        String reference = taskReference(torrentsInfo);
        if (StrUtil.isBlank(reference)) {
            return DownloaderResult.rejected("TRANSMISSION_RECOVERY_ID_MISSING");
        }
        Config config = configuration();
        SessionState state = ensureSession(config);
        boolean verified = TransmissionRpcCodec.success(
                rpc(config, TransmissionRpcBody.torrentVerify(reference), state).body(),
                state.dialect());
        if (!verified) {
            return DownloaderResult.rejected("TRANSMISSION_VERIFY_REJECTED");
        }
        boolean started = TransmissionRpcCodec.success(
                rpc(config, TransmissionRpcBody.torrentStart(reference), state).body(),
                state.dialect());
        return started ? DownloaderResult.success(null)
                : DownloaderResult.rejected("TRANSMISSION_START_REJECTED");
    }

    @Override
    public Boolean rename(TorrentsInfo torrentsInfo) {
        String reference = taskReference(torrentsInfo);
        String name = torrentsInfo.getName();
        if (ReUtil.contains("^\\w{40}$", name)) {
            return false;
        }
        String reName = renameTarget(torrentsInfo, reference);
        if (StrUtil.isBlank(reName)) {
            return false;
        }
        String extName = FileUtil.extName(name);
        if (StrUtil.isNotBlank(extName)) {
            reName = reName + "." + extName;
        }

        Config config = configuration();
        SessionState state = ensureSession(config);
        boolean ok = TransmissionRpcCodec.success(
                rpc(config, TransmissionRpcBody.torrentRenamePath(reference, name, reName), state).body(),
                state.dialect());
        Assert.isTrue(ok, "重命名失败 {} ==> {}", name, reName);
        removeRenameTargets(torrentsInfo, reference);
        return true;
    }

    @Override
    public Boolean addTags(TorrentsInfo torrentsInfo, String tag) {
        List<String> tags = new ArrayList<>(Optional.ofNullable(torrentsInfo.getTagList()).orElseGet(List::of));
        if (!tags.contains(tag)) {
            tags.add(tag);
        }
        Config config = configuration();
        SessionState state = ensureSession(config);
        return TransmissionRpcCodec.success(
                rpc(config, TransmissionRpcBody.torrentSet(taskReference(torrentsInfo), tags), state).body(),
                state.dialect());
    }

    @Override
    public void updateTrackers(Set<String> trackers) {
        log.info("Transmission 暂不支持自动更新 Trackers");
    }

    @Override
    public void setSavePath(TorrentsInfo torrentsInfo, String path) {
        Config config = configuration();
        SessionState state = ensureSession(config);
        boolean ok = TransmissionRpcCodec.success(
                rpc(config, TransmissionRpcBody.torrentSetLocation(taskReference(torrentsInfo), path), state).body(),
                state.dialect());
        Assert.isTrue(ok, "Transmission 修改保存位置失败");
    }

    private static String responseReference(JsonObject added) {
        if (added == null) {
            return "";
        }
        for (String field : List.of("hash_string", "hashString", "id")) {
            if (added.has(field) && !added.get(field).isJsonNull()) {
                String value = added.get(field).getAsString();
                if (StrUtil.isNotBlank(value)) {
                    return value;
                }
            }
        }
        return "";
    }

    private static String taskReference(TorrentsInfo torrentsInfo) {
        if (torrentsInfo == null) {
            return "";
        }
        return StrUtil.blankToDefault(
                StrUtil.blankToDefault(torrentsInfo.getHash(), torrentsInfo.getId()), "");
    }

    private static String renameTarget(TorrentsInfo torrentsInfo, String reference) {
        String target = RenameCacheUtil.get(reference);
        if (StrUtil.isBlank(target) && !reference.equals(torrentsInfo.getId())) {
            target = RenameCacheUtil.get(torrentsInfo.getId());
        }
        if (StrUtil.isBlank(target) && !reference.equals(torrentsInfo.getHash())) {
            target = RenameCacheUtil.get(torrentsInfo.getHash());
        }
        return target;
    }

    private static void removeRenameTargets(TorrentsInfo torrentsInfo, String reference) {
        RenameCacheUtil.remove(reference);
        if (StrUtil.isNotBlank(torrentsInfo.getId()) && !reference.equals(torrentsInfo.getId())) {
            RenameCacheUtil.remove(torrentsInfo.getId());
        }
        if (StrUtil.isNotBlank(torrentsInfo.getHash()) && !reference.equals(torrentsInfo.getHash())) {
            RenameCacheUtil.remove(torrentsInfo.getHash());
        }
    }

    private SessionState ensureSession(Config config) {
        String key = fingerprint(config);
        SessionState cached = sessions.get(key);
        if (cached != null) {
            return cached;
        }

        long id = requestIds.getAndIncrement();
        RpcResponse modern = send(config, TransmissionRpcBody.sessionGet(), TransmissionDialect.JSON_RPC_2, "", id);
        if (modern.status() >= 200 && modern.status() < 300 &&
                TransmissionRpcCodec.looksLikeJsonRpc2(modern.body()) &&
                TransmissionRpcCodec.success(modern.body(), TransmissionDialect.JSON_RPC_2)) {
            SessionState state = new SessionState(TransmissionDialect.JSON_RPC_2, modern.sessionId());
            sessions.putIfAbsent(key, state);
            return sessions.get(key);
        }

        if (modern.status() == 401 || modern.status() == 403 || modern.status() >= 500) {
            throw DownloaderOperationException.http("TRANSMISSION", modern.status());
        }

        RpcResponse legacy = send(config, TransmissionRpcBody.sessionGet(), TransmissionDialect.LEGACY,
                modern.sessionId(), requestIds.getAndIncrement());
        if (legacy.status() < 200 || legacy.status() >= 300) {
            throw DownloaderOperationException.http("TRANSMISSION", legacy.status());
        }
        if (!TransmissionRpcCodec.success(legacy.body(), TransmissionDialect.LEGACY)) {
            throw DownloaderOperationException.rejected("TRANSMISSION_APPLICATION_REJECTED");
        }
        SessionState state = new SessionState(TransmissionDialect.LEGACY,
                StrUtil.blankToDefault(legacy.sessionId(), modern.sessionId()));
        sessions.putIfAbsent(key, state);
        return sessions.get(key);
    }

    private RpcResponse rpc(Config config, TransmissionRpcBody body, SessionState state) {
        RpcResponse response = send(config, body, state.dialect(), state.sessionId(), requestIds.getAndIncrement());
        if (response.sessionId() != null && !response.sessionId().equals(state.sessionId())) {
            state.sessionId(response.sessionId());
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw DownloaderOperationException.http("TRANSMISSION", response.status());
        }
        if (!TransmissionRpcCodec.success(response.body(), state.dialect())) {
            throw DownloaderOperationException.rejected("TRANSMISSION_APPLICATION_REJECTED");
        }
        return response;
    }

    private RpcResponse send(Config config, TransmissionRpcBody body, TransmissionDialect dialect,
                             String sessionId, long id) {
        String currentSession = sessionId;
        int sessionRetries = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            String username = config.getDownloadToolUsername();
            String password = config.getDownloadToolPassword();
            HttpRequest request = HttpReq.post(config.getDownloadToolHost() + "/transmission/rpc", config)
                    .header("Content-Type", "application/json")
                    .header("X-Transmission-Session-Id", StrUtil.blankToDefault(currentSession, ""))
                    .body(TransmissionRpcCodec.encode(body, dialect, id).toString());
            if (StrUtil.isAllNotBlank(username, password)) {
                request.header(Header.AUTHORIZATION,
                        StrFormatter.format("Basic {}", Base64.encode(username + ":" + password)));
            }
            try (HttpResponse response = request.execute()) {
                int status = response.getStatus();
                String responseSession = response.header("X-Transmission-Session-Id");
                if (status == 409 && StrUtil.isNotBlank(responseSession) && sessionRetries++ == 0) {
                    currentSession = responseSession;
                    attempt--;
                    continue;
                }
                if (status >= 500 && status < 600 && attempt < 2) {
                    ThreadUtil.sleep(250L * (1L << attempt));
                    continue;
                }
                return new RpcResponse(status, response.body(),
                        StrUtil.blankToDefault(responseSession, currentSession));
            } catch (RuntimeException e) {
                if (!DownloaderFailures.isRetryable(e) || attempt == 2) {
                    throw e;
                }
                ThreadUtil.sleep(250L * (1L << attempt));
            }
        }
        throw DownloaderOperationException.failed("TRANSMISSION_RETRY_EXHAUSTED", true);
    }

    private static String fingerprint(Config config) {
        return SecureUtil.sha256(StrUtil.blankToDefault(config.getDownloadToolHost(), "") + "\0" +
                StrUtil.blankToDefault(config.getDownloadToolUsername(), "") + "\0" +
                StrUtil.blankToDefault(config.getDownloadToolPassword(), "") + "\0" +
                String.valueOf(config.getProxy()) + "\0" +
                StrUtil.blankToDefault(config.getProxyHost(), "") + "\0" +
                String.valueOf(config.getProxyPort()));
    }

    private Config configuration() {
        Config configured = activeConfig;
        return configured == null ? ConfigUtil.copy(ConfigUtil.CONFIG) : configured;
    }

    private static final class SessionState {
        private final TransmissionDialect dialect;
        private volatile String sessionId;

        private SessionState(TransmissionDialect dialect, String sessionId) {
            this.dialect = dialect;
            this.sessionId = sessionId;
        }

        private TransmissionDialect dialect() {
            return dialect;
        }

        private String sessionId() {
            return sessionId;
        }

        private void sessionId(String value) {
            this.sessionId = value;
        }
    }

    private record RpcResponse(int status, String body, String sessionId) {
    }
}
