package ani.rss.completion;

import ani.rss.commons.FileUtils;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.recovery.MissingEpisodeRecoveryService;
import ani.rss.service.DownloadService;
import ani.rss.service.SubscriptionDeletionService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Safely finalizes a completed migration by removing subscription metadata only. */
@Slf4j
@Service
public class CompletionMigrationService {
    private final CompletionMigrationRepository repository;
    private final OwnershipService ownershipService;
    private final DownloadService downloadService;
    private final SubscriptionDeletionService deletionService;
    private final MissingEpisodeRecoveryService recoveryService;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();

    public CompletionMigrationService(
            CompletionMigrationRepository repository,
            OwnershipService ownershipService,
            DownloadService downloadService,
            SubscriptionDeletionService deletionService,
            MissingEpisodeRecoveryService recoveryService) {
        this.repository = repository;
        this.ownershipService = ownershipService;
        this.downloadService = downloadService;
        this.deletionService = deletionService;
        this.recoveryService = recoveryService;
    }

    public void complete(Ani source) {
        if (source == null || StrUtil.isBlank(source.getId())) {
            return;
        }
        Object lock = locks.computeIfAbsent(source.getId(), ignored -> new Object());
        synchronized (lock) {
            try {
                current(source.getId()).ifPresent(this::moveAndFinalize);
            } finally {
                locks.remove(source.getId(), lock);
            }
        }
    }

    /** Reconciles a crash after the move but before its durable finalization state. */
    public void reconcilePendingFinalizations() {
        for (CompletionMigrationRecord record : repository.listPendingFinalization()) {
            Object lock = locks.computeIfAbsent(record.subscriptionId(), ignored -> new Object());
            synchronized (lock) {
                try {
                    if (record.state() == CompletionMigrationState.MOVED ||
                            ownershipService.isSubscriptionAtRoot(record.subscriptionId(), record.targetRoot())) {
                        repository.setState(record.subscriptionId(), CompletionMigrationState.MOVED);
                        finalizeMoved(new CompletionMigrationRecord(
                                record.subscriptionId(), record.subscriptionFingerprint(), record.targetRoot(),
                                CompletionMigrationState.MOVED, record.createdAt(), System.currentTimeMillis()));
                    }
                } catch (Exception e) {
                    log.warn("完结迁移最终化待重试 subscriptionId:{} type:{}", record.subscriptionId(),
                            e.getClass().getSimpleName());
                } finally {
                    locks.remove(record.subscriptionId(), lock);
                }
            }
        }
    }

    private void moveAndFinalize(Ani ani) {
        CompletionTarget target = target(ani);
        if (target == null) {
            return;
        }
        CompletionMigrationRecord record = repository.prepare(ani.getId(), target.fingerprint(), target.newRoot());
        if (record.state() == CompletionMigrationState.CONFLICT || record.state() == CompletionMigrationState.FINALIZED) {
            return;
        }
        if (record.state() == CompletionMigrationState.MOVED) {
            finalizeMoved(record);
            return;
        }
        if (ownershipService.isSubscriptionAtRoot(ani.getId(), target.newRoot())) {
            repository.setState(ani.getId(), CompletionMigrationState.MOVED);
            finalizeMoved(new CompletionMigrationRecord(ani.getId(), target.fingerprint(), target.newRoot(),
                    CompletionMigrationState.MOVED, record.createdAt(), System.currentTimeMillis()));
            return;
        }

        boolean moveMayHaveCompleted = false;
        try {
            List<OwnershipService.VerifiedOwnedFile> verified = ownershipService.verifiedSubscriptionFiles(ani.getId());
            if (verified.isEmpty()) {
                throw new IllegalStateException("completed migration has no verified owned media");
            }
            ownershipService.validateSubscriptionMove(ani.getId(), target.newRoot());

            List<TorrentsInfo> ownedTasks = TorrentUtil.getTorrentsInfos().stream()
                    .filter(task -> ownershipService.belongsTo(task, ani.getId()))
                    .toList();
            for (TorrentsInfo task : ownedTasks) {
                TorrentUtil.setSavePath(task, target.newRoot());
            }
            if (!ownedTasks.isEmpty()) {
                ThreadUtil.sleep(3000);
            }
            ownershipService.moveSubscriptionFiles(ani.getId(), target.newRoot());
            verifyMoved(ani.getId(), target.newRoot());
            moveMayHaveCompleted = true;
            repository.setState(ani.getId(), CompletionMigrationState.MOVED);
            finalizeMoved(new CompletionMigrationRecord(ani.getId(), target.fingerprint(), target.newRoot(),
                    CompletionMigrationState.MOVED, record.createdAt(), System.currentTimeMillis()));
            log.info("订阅完结迁移并最终化 subscriptionId:{}", ani.getId());
        } catch (Exception e) {
            // A process can fail after files and ownership roots are changed
            // but before MOVED reaches SQLite. Preserve PREPARED so startup
            // can prove the target state and finalize metadata safely.
            if (moveMayHaveCompleted || ownershipService.isSubscriptionAtRoot(ani.getId(), target.newRoot())) {
                repository.setState(ani.getId(), CompletionMigrationState.PREPARED);
            } else {
                repository.setState(ani.getId(), CompletionMigrationState.FAILED);
            }
            throw e instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("completed migration failed", e);
        }
    }

    private void finalizeMoved(CompletionMigrationRecord record) {
        Optional<Ani> current = current(record.subscriptionId());
        if (current.isEmpty()) {
            recoveryService.cancelSubscription(record.subscriptionId());
            repository.setState(record.subscriptionId(), CompletionMigrationState.FINALIZED);
            return;
        }
        Ani ani = current.get();
        if (!record.subscriptionFingerprint().equals(fingerprint(ani))) {
            repository.setState(record.subscriptionId(), CompletionMigrationState.CONFLICT);
            return;
        }
        verifyMoved(record.subscriptionId(), record.targetRoot());
        deletionService.deleteWithoutFiles(List.of(record.subscriptionId()));
        recoveryService.cancelSubscription(record.subscriptionId());
        repository.setState(record.subscriptionId(), CompletionMigrationState.FINALIZED);
    }

    private CompletionTarget target(Ani ani) {
        if (!Boolean.TRUE.equals(ani.getCompleted()) || ani.getTotalEpisodeNumber() == null ||
                ani.getTotalEpisodeNumber() < 1 || ani.getCurrentEpisodeNumber() == null ||
                ani.getCurrentEpisodeNumber() < ani.getTotalEpisodeNumber() || Boolean.TRUE.equals(ani.getEnable()) ||
                Boolean.TRUE.equals(ani.getOva())) {
            return null;
        }
        Config config = ConfigUtil.snapshot();
        if (!Boolean.TRUE.equals(config.getAutoDisabled()) || !Boolean.TRUE.equals(config.getCompleted())) {
            return null;
        }
        String template = Boolean.TRUE.equals(ani.getCustomCompleted())
                ? ani.getCustomCompletedPathTemplate() : config.getCompletedPathTemplate();
        if (StrUtil.isBlank(template)) {
            return null;
        }
        Ani pathAni = ObjectUtil.clone(ani);
        Config pathConfig = ObjectUtil.clone(config);
        pathConfig.setDownloadPathTemplate(template);
        pathAni.setCustomDownloadPath(false);
        String oldRoot = downloadService.getDownloadPath(ObjectUtil.clone(ani), config);
        String newRoot = downloadService.getDownloadPath(pathAni, pathConfig);
        if (!FileUtil.exist(oldRoot) || StrUtil.equals(FileUtils.getAbsolutePath(oldRoot),
                FileUtils.getAbsolutePath(newRoot))) {
            return null;
        }
        return new CompletionTarget(FileUtils.getAbsolutePath(oldRoot), FileUtils.getAbsolutePath(newRoot),
                fingerprint(ani));
    }

    private void verifyMoved(String subscriptionId, String targetRoot) {
        String normalized = FileUtils.getAbsolutePath(targetRoot);
        List<DownloadOwnership> active = ownershipService.listBySubscription(subscriptionId).stream()
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED)
                .toList();
        if (active.isEmpty() || active.stream().anyMatch(ownership ->
                !normalized.equals(FileUtils.getAbsolutePath(ownership.saveRoot())))) {
            throw new IllegalStateException("completed migration roots were not fully updated");
        }
        List<OwnershipService.VerifiedOwnedFile> verified = ownershipService.verifiedSubscriptionFiles(subscriptionId);
        if (verified.isEmpty()) {
            throw new IllegalStateException("completed migration has no verified files at target");
        }
        Path root = Path.of(normalized).toAbsolutePath().normalize();
        if (verified.stream().anyMatch(file -> !file.path().startsWith(root))) {
            throw new IllegalStateException("completed migration target verification failed");
        }
    }

    private static Optional<Ani> current(String subscriptionId) {
        return AniUtil.snapshot().stream().filter(ani -> subscriptionId.equals(ani.getId())).findFirst();
    }

    private static String fingerprint(Ani ani) {
        String value = String.join("\u0000",
                StrUtil.blankToDefault(ani.getId(), ""),
                StrUtil.blankToDefault(ani.getUrl(), ""),
                StrUtil.blankToDefault(ani.getTitle(), ""),
                String.valueOf(ani.getSeason()),
                String.valueOf(ani.getTotalEpisodeNumber()),
                String.valueOf(ani.getCurrentEpisodeNumber()),
                String.valueOf(ani.getCompleted()),
                String.valueOf(ani.getCustomCompleted()),
                StrUtil.blankToDefault(ani.getCustomCompletedPathTemplate(), ""));
        return SecureUtil.sha256(value);
    }

    private record CompletionTarget(String oldRoot, String newRoot, String fingerprint) {
    }
}
