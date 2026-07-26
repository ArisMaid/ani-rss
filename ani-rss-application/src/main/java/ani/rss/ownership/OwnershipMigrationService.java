package ani.rss.ownership;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsTagEnum;
import ani.rss.service.DownloadService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class OwnershipMigrationService {
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
        if (!TorrentUtil.login()) {
            return candidates;
        }
        String downloaderType = ConfigUtil.CONFIG.getDownloadToolType();
        for (TorrentsInfo task : downloaderTasks()) {
            if (ownershipService.findOwned(task).isPresent() || !hasAniRssTag(task) || StrUtil.isBlank(task.getHash())) {
                continue;
            }
            List<Ani> matches = strictMatches(task);
            if (matches.size() == 1) {
                Ani ani = matches.get(0);
                candidates.add(candidate(task, ani, true, "标签、info-hash、唯一订阅和保存路径全部匹配"));
                continue;
            }
            if (matches.isEmpty()) {
                candidates.add(candidate(task, null, false, "未找到同时匹配缓存种子和保存路径的唯一订阅"));
            } else {
                candidates.add(candidate(task, null, false, "存在多个可能订阅，必须人工确认"));
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
        TorrentsInfo task = downloaderTasks().stream()
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
                ConfigUtil.CONFIG.getDownloadToolType(),
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

    private static List<TorrentsInfo> downloaderTasks() {
        if (TorrentUtil.CLIENT == null) {
            return List.of();
        }
        List<TorrentsInfo> tasks = TorrentUtil.CLIENT.torrents().value();
        return tasks == null ? List.of() : tasks;
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
                .filter(File::isFile)
                .map(FileUtil::mainName)
                .anyMatch(name -> name.equalsIgnoreCase(infoHash));
    }

    private static boolean hasAniRssTag(TorrentsInfo task) {
        return Optional.ofNullable(task.getTagList())
                .orElseGet(List::of)
                .contains(TorrentsTagEnum.ANI_RSS.getValue());
    }

    private OwnershipCandidate candidate(TorrentsInfo task, Ani ani, boolean autoAdoptable, String reason) {
        return new OwnershipCandidate(
                ConfigUtil.CONFIG.getDownloadToolType(),
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
