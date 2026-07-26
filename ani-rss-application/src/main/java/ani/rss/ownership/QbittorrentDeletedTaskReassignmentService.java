package ani.rss.ownership;

import ani.rss.commons.FileUtils;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.download.qBittorrent;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.TorrentsTagEnum;
import cn.hutool.core.util.StrUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/** Safely reattaches a qBittorrent task left by an explicitly deleted subscription. */
@Slf4j
@Service
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP2",
        justification = "Spring-managed collaborators are retained by identity and never exposed as DTO state")
public class QbittorrentDeletedTaskReassignmentService {
    private final OwnershipService ownershipService;
    private final OwnershipRepository repository;

    public QbittorrentDeletedTaskReassignmentService(
            OwnershipService ownershipService, OwnershipRepository repository) {
        this.ownershipService = ownershipService;
        this.repository = repository;
    }

    /**
     * qBittorrent rejects a re-add of an existing hash with HTTP 409.  Reuse
     * the old task only when the local reassignment audit and the remote task
     * prove it was owned by the deleted subscription.
     */
    public boolean reattach(DownloaderClient client, DownloadOwnership ownership) {
        if (!isQbittorrent(client) || ownership == null ||
                (ownership.state() != OwnershipState.PENDING && ownership.state() != OwnershipState.FAILED)) {
            return false;
        }
        Optional<OwnershipReassignment> history = repository.findDeletedReassignment(ownership);
        if (history.isEmpty()) {
            return false;
        }
        OwnershipReassignment reassignment = history.get();
        if (StrUtil.isBlank(reassignment.remoteTaskId()) ||
                StrUtil.isBlank(reassignment.previousSubscriptionId()) ||
                StrUtil.isBlank(reassignment.previousSaveRoot())) {
            return false;
        }

        DownloaderResult<List<TorrentsInfo>> listed = client.torrents();
        if (!listed.isSuccess()) {
            log.warn("无法核验重订阅遗留 qB 任务 code:{}", listed.errorCode());
            return false;
        }
        TorrentsInfo candidate = exactTask(listed.value(), reassignment, ownership, false).orElse(null);
        if (candidate == null) {
            return false;
        }

        if (!hasTag(candidate, ownership.subscriptionId())) {
            DownloaderResult<Void> tagged = client.addTags(candidate, ownership.subscriptionId());
            if (!tagged.isSuccess()) {
                log.warn("无法接回重订阅遗留 qB 任务 code:{}", tagged.errorCode());
                return false;
            }
        }

        // Re-read after the remote mutation so an intervening change cannot
        // turn a tag operation into ownership of a different task.
        DownloaderResult<List<TorrentsInfo>> verified = client.torrents();
        if (!verified.isSuccess()) {
            log.warn("无法复核重订阅遗留 qB 任务 code:{}", verified.errorCode());
            return false;
        }
        TorrentsInfo verifiedTask = exactTask(verified.value(), reassignment, ownership, true).orElse(null);
        if (verifiedTask == null) {
            return false;
        }

        ownershipService.activate(ownership.ownershipId(), remoteIdentity(verifiedTask), null);
        try {
            ownershipService.captureFiles(ownership.ownershipId(), verifiedTask);
        } catch (RuntimeException e) {
            // The remote task is already verified and tagged. A later normal
            // refresh can capture its manifest without resubmitting it.
            log.warn("已接回 qB 任务但暂无法读取文件清单 type:{}", e.getClass().getSimpleName());
        }
        log.info("已接回删除订阅遗留的 qB 任务 hash:{}", shortHash(ownership.infoHash()));
        return true;
    }

    private static Optional<TorrentsInfo> exactTask(
            List<TorrentsInfo> tasks,
            OwnershipReassignment reassignment,
            DownloadOwnership ownership,
            boolean requireReplacementTag) {
        if (tasks == null) {
            return Optional.empty();
        }
        List<TorrentsInfo> matches = tasks.stream()
                .filter(task -> task != null)
                .filter(task -> StrUtil.equalsIgnoreCase(task.getHash(), reassignment.infoHash()))
                .filter(task -> StrUtil.equalsIgnoreCase(remoteIdentity(task), reassignment.remoteTaskId()))
                .filter(task -> samePath(task.getSavePath(), reassignment.previousSaveRoot()))
                .filter(task -> samePath(task.getSavePath(), ownership.saveRoot()))
                .filter(task -> hasTag(task, TorrentsTagEnum.ANI_RSS.getValue()))
                .filter(task -> hasTag(task, reassignment.previousSubscriptionId()))
                .filter(task -> !requireReplacementTag || hasTag(task, ownership.subscriptionId()))
                .toList();
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static boolean isQbittorrent(DownloaderClient client) {
        return client != null && client.adapter() instanceof qBittorrent &&
                "qBittorrent".equalsIgnoreCase(client.configurationSnapshot().getDownloadToolType());
    }

    private static boolean hasTag(TorrentsInfo task, String tag) {
        return StrUtil.isNotBlank(tag) && task.getTagList() != null && task.getTagList().contains(tag);
    }

    private static boolean samePath(String left, String right) {
        return StrUtil.isNotBlank(left) && StrUtil.isNotBlank(right) &&
                StrUtil.equals(FileUtils.getAbsolutePath(left), FileUtils.getAbsolutePath(right));
    }

    private static String remoteIdentity(TorrentsInfo task) {
        return StrUtil.blankToDefault(task.getId(), task.getHash());
    }

    private static String shortHash(String hash) {
        return StrUtil.isBlank(hash) ? "unknown" : hash.substring(0, Math.min(12, hash.length()));
    }
}
