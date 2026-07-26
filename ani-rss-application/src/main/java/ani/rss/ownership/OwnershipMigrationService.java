package ani.rss.ownership;

import ani.rss.commons.FileUtils;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.bittorrent.TorrentFile;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class OwnershipMigrationService {
    private static final Pattern MAGNET_INFO_HASH = Pattern.compile(
            "(?i)(?:[?&])xt=urn:btih:([^&]+)");
    private final OwnershipService ownershipService;
    private final OwnershipRepository repository;
    private final DownloadService downloadService;

    public OwnershipMigrationService(
            OwnershipService ownershipService,
            OwnershipRepository repository,
            DownloadService downloadService) {
        this.ownershipService = ownershipService;
        this.repository = repository;
        this.downloadService = downloadService;
    }

    public List<OwnershipCandidate> scan() {
        List<OwnershipCandidate> candidates = new ArrayList<>();
        DownloaderClient activeClient = TorrentUtil.client();
        if (activeClient == null || !activeClient.connect(false).isSuccess()) {
            return candidates;
        }
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        for (TorrentsInfo task : downloaderTasks(activeClient)) {
            if (ownershipService.findOwned(downloaderType, task).isPresent() ||
                    !hasAniRssTag(task) || StrUtil.isBlank(task.getHash())) {
                continue;
            }
            List<Ani> matches = strictMatches(task);
            if (matches.size() == 1) {
                Ani ani = matches.get(0);
                candidates.add(candidate(downloaderType, task, ani, true,
                        "标签、info-hash、唯一订阅和保存路径全部匹配"));
                continue;
            }
            if (matches.isEmpty()) {
                candidates.add(candidate(downloaderType, task, null, false,
                        "未找到同时匹配缓存种子和保存路径的唯一订阅"));
            } else {
                candidates.add(candidate(downloaderType, task, null, false,
                        "存在多个可能订阅，必须人工确认"));
            }
        }
        return candidates;
    }

    public int adoptStrictCandidates() {
        int adopted = 0;
        for (OwnershipCandidate candidate : scan()) {
            if (!candidate.autoAdoptable()) {
                continue;
            }
            adopt(candidate.remoteTaskId(), candidate.infoHash(), candidate.subscriptionId(), true);
            adopted++;
        }
        if (adopted > 0) {
            log.info("已严格接管 {} 个旧下载任务", adopted);
        }
        return adopted;
    }

    public DownloadOwnership adopt(
            String remoteTaskId,
            String infoHash,
            String subscriptionId,
            boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("人工接管必须显式确认");
        }
        DownloaderClient activeClient = Optional.ofNullable(TorrentUtil.client())
                .orElseThrow(() -> new IllegalStateException("下载工具尚未初始化"));
        String downloaderType = activeClient.configurationSnapshot().getDownloadToolType();
        TorrentsInfo task = downloaderTasks(activeClient).stream()
                .filter(info -> StrUtil.equals(info.getId(), remoteTaskId) ||
                        StrUtil.equalsIgnoreCase(info.getHash(), infoHash))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("下载任务不存在"));
        if (!hasAniRssTag(task)) {
            throw new IllegalArgumentException("任务缺少 ani-rss 标签，拒绝接管");
        }
        Ani ani = AniUtil.ANI_LIST.stream()
                .filter(value -> value.getId().equals(subscriptionId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("订阅不存在"));
        if (StrUtil.isBlank(task.getHash())) {
            throw new IllegalArgumentException("任务缺少 info-hash，拒绝接管");
        }

        long now = System.currentTimeMillis();
        DownloadOwnership ownership = repository.createPending(new DownloadOwnership(
                UUID.randomUUID().toString(),
                downloaderType,
                task.getId(),
                task.getHash(),
                ani.getId(),
                ani.getSeason(),
                null,
                FileUtils.getAbsolutePath(task.getSavePath()),
                OwnershipState.LEGACY_ADOPTED,
                now,
                now
        ));
        repository.updateState(ownership.ownershipId(), OwnershipState.LEGACY_ADOPTED);
        ownershipService.captureFiles(ownership.ownershipId(), task);
        return repository.find(ownership.ownershipId()).orElseThrow();
    }

    private static List<TorrentsInfo> downloaderTasks(DownloaderClient activeClient) {
        DownloaderResult<List<TorrentsInfo>> result = activeClient.torrents();
        return result.isSuccess() && result.value() != null ? List.copyOf(result.value()) : List.of();
    }

    private List<Ani> strictMatches(TorrentsInfo task) {
        return AniUtil.ANI_LIST.stream()
                .filter(ani -> downloadService.getDownloadPath(ani).equals(task.getSavePath()))
                .filter(ani -> hasCachedTorrent(ani, task.getHash()))
                .toList();
    }

    private static boolean hasCachedTorrent(Ani ani, String infoHash) {
        File torrentDir = TorrentUtil.getTorrentDir(ani);
        return FileUtils.listFileList(torrentDir).stream()
                .anyMatch(file -> cachedTorrentMatches(file, infoHash));
    }

    static boolean cachedTorrentMatches(File file, String infoHash) {
        if (file == null || StrUtil.isBlank(infoHash) ||
                !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(file.toPath()) ||
                !FileUtil.mainName(file).equalsIgnoreCase(infoHash)) {
            return false;
        }
        try {
            String extension = FileUtil.extName(file);
            if ("torrent".equalsIgnoreCase(extension)) {
                synchronized (TorrentFile.class) {
                    return infoHash.equalsIgnoreCase(new TorrentFile(file).getHexHash());
                }
            }
            if ("txt".equalsIgnoreCase(extension)) {
                String value = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                Matcher matcher = MAGNET_INFO_HASH.matcher(value);
                return matcher.find() && infoHash.equalsIgnoreCase(matcher.group(1));
            }
        } catch (Exception e) {
            log.warn("旧任务缓存种子校验失败 type:{}", e.getClass().getSimpleName());
        }
        return false;
    }

    private static boolean hasAniRssTag(TorrentsInfo task) {
        return Optional.ofNullable(task.getTagList())
                .orElseGet(List::of)
                .contains(TorrentsTagEnum.ANI_RSS.getValue());
    }

    private OwnershipCandidate candidate(
            String downloaderType, TorrentsInfo task, Ani ani, boolean autoAdoptable, String reason) {
        return new OwnershipCandidate(
                downloaderType,
                task.getId(),
                task.getHash(),
                task.getName(),
                task.getSavePath(),
                ani == null ? null : ani.getId(),
                ani == null ? null : ani.getTitle(),
                autoAdoptable,
                reason
        );
    }
}
