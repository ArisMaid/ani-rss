package ani.rss.service;

import ani.rss.download.DownloaderResult;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.recovery.MissingEpisodeRecoveryService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deletes subscriptions and only the content whose ownership can be proved. */
@Service
public final class SubscriptionDeletionService {
    private final OwnershipService ownershipService;
    private final SubscriptionStore subscriptionStore;
    private final RemoteTaskGateway remoteTasks;
    private final MissingEpisodeRecoveryService recoveryService;
    private final SubscriptionDownloadPathResolver downloadPathResolver;
    private final Object deletionLock = new Object();

    @Autowired
    public SubscriptionDeletionService(
            OwnershipService ownershipService,
            DownloadService downloadService,
            MissingEpisodeRecoveryService recoveryService) {
        this(ownershipService, new AniUtilSubscriptionStore(), new TorrentRemoteTaskGateway(), recoveryService,
                downloadService::getDownloadPath);
    }

    SubscriptionDeletionService(
            OwnershipService ownershipService,
            SubscriptionStore subscriptionStore,
            RemoteTaskGateway remoteTasks) {
        this(ownershipService, subscriptionStore, remoteTasks, null, ignored -> null);
    }

    SubscriptionDeletionService(
            OwnershipService ownershipService,
            SubscriptionStore subscriptionStore,
            RemoteTaskGateway remoteTasks,
            SubscriptionDownloadPathResolver downloadPathResolver) {
        this(ownershipService, subscriptionStore, remoteTasks, null, downloadPathResolver);
    }

    SubscriptionDeletionService(
            OwnershipService ownershipService,
            SubscriptionStore subscriptionStore,
            RemoteTaskGateway remoteTasks,
            MissingEpisodeRecoveryService recoveryService,
            SubscriptionDownloadPathResolver downloadPathResolver) {
        this.ownershipService = Objects.requireNonNull(ownershipService, "ownershipService");
        this.subscriptionStore = Objects.requireNonNull(subscriptionStore, "subscriptionStore");
        this.remoteTasks = Objects.requireNonNull(remoteTasks, "remoteTasks");
        this.recoveryService = recoveryService;
        this.downloadPathResolver = downloadPathResolver == null ? ignored -> null : downloadPathResolver;
    }

    /** Removes subscription metadata only; owned media and remote tasks stay intact. */
    public DeletionResult deleteWithoutFiles(Collection<String> subscriptionIds) {
        return delete(subscriptionIds, false, false, false);
    }

    public DeletionResult delete(Collection<String> subscriptionIds, boolean deleteFiles) {
        return delete(subscriptionIds, deleteFiles, true, true);
    }

    private DeletionResult delete(
            Collection<String> subscriptionIds,
            boolean deleteFiles,
            boolean deleteRemoteTasks,
            boolean releaseOwnership) {
        synchronized (deletionLock) {
            LinkedHashSet<String> ids = normalizedIds(subscriptionIds);
            List<Ani> current = subscriptionStore.snapshot();
            Map<String, Ani> byId = subscriptionsById(current);
            for (String id : ids) {
                if (!byId.containsKey(id)) {
                    throw new IllegalArgumentException("subscription does not exist: " + id);
                }
            }

            List<DownloadOwnership> subscriptionOwnerships = ids.stream()
                    .flatMap(id -> ownershipService.listBySubscription(id).stream())
                    .toList();
            List<DownloadOwnership> ownerships = subscriptionOwnerships.stream()
                    .filter(SubscriptionDeletionService::isActive)
                    .toList();
            List<DownloadOwnership> deletableOwnerships = releaseOwnership
                    ? subscriptionOwnerships.stream()
                            .filter(SubscriptionDeletionService::isDeletable)
                            .toList()
                    : List.of();
            OwnershipService.FileDeletionPreparation fileDeletion =
                    new OwnershipService.FileDeletionPreparation(List.of(), 0);
            List<TorrentsInfo> ownedTasks = List.of();
            if (deleteRemoteTasks) {
                for (DownloadOwnership ownership : ownerships) {
                    if (!remoteTasks.supports(ownership.downloaderType())) {
                        throw new IllegalStateException(
                                "active downloader cannot safely remove ownership " + ownership.ownershipId());
                    }
                }
                ownedTasks = matchOwnedTasks(remoteTasks.list(), ownerships);
            }
            if (deleteFiles) {
                fileDeletion = ownershipService.prepareSubscriptionFileDeletionBestEffort(ids);
            }
            // A user may elect to keep local media, while the registered
            // subscription directory is already empty (for example after a
            // failed submission or an earlier manual move).  In that case an
            // empty, template-scoped directory is still stale subscription
            // scaffolding.  Completion finalization passes releaseOwnership
            // as false, so it intentionally keeps its migrated directories.
            Config configSnapshot = ConfigUtil.snapshot();
            Map<String, Path> directoryCleanupBoundaries = releaseOwnership
                    ? SubscriptionDirectoryCleanupPolicy.resolveBoundaries(
                            byId, deletableOwnerships, configSnapshot)
                    : Map.of();
            List<OwnershipService.DirectoryCleanupTarget> inferredDirectoryCleanupTargets = releaseOwnership
                    ? resolveInferredDirectoryCleanupTargets(
                            ids, byId, configSnapshot)
                    : List.of();

            for (TorrentsInfo task : ownedTasks) {
                remoteTasks.deleteTaskOnly(task);
            }
            OwnershipService.FileDeletionResult fileDeletionResult = deleteFiles
                    ? ownershipService.deletePreparedFilesBestEffortWithDetails(fileDeletion.files())
                    : new OwnershipService.FileDeletionResult(List.of(), 0);
            // Explicit user deletion releases managed identities before the
            // subscription list is persisted. Completion finalization keeps
            // them active because its files and seeding tasks intentionally remain.
            ownershipService.markDeleted(deletableOwnerships);
            if (releaseOwnership) {
                ownershipService.pruneEmptyDirectoriesAfterDeletion(
                        fileDeletionResult.deletedFiles(), deletableOwnerships, directoryCleanupBoundaries,
                        inferredDirectoryCleanupTargets);
            }
            if (recoveryService != null) {
                for (String id : ids) {
                    recoveryService.cancelSubscription(id);
                }
            }
            List<Ani> candidate = current.stream()
                    .filter(ani -> !ids.contains(ani.getId()))
                    .toList();
            subscriptionStore.commit(candidate);
            return new DeletionResult(
                    ids.size(),
                    ownedTasks.size(),
                    fileDeletionResult.deletedFiles().size(),
                    fileDeletion.skippedFiles() + fileDeletionResult.skippedFiles());
        }
    }

    private static LinkedHashSet<String> normalizedIds(Collection<String> ids) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (ids != null) {
            ids.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .forEach(normalized::add);
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("at least one subscription id is required");
        }
        return normalized;
    }

    private List<OwnershipService.DirectoryCleanupTarget> resolveInferredDirectoryCleanupTargets(
            Collection<String> subscriptionIds,
            Map<String, Ani> subscriptions,
            Config config) {
        List<OwnershipService.DirectoryCleanupTarget> targets = new java.util.ArrayList<>();
        for (String id : subscriptionIds) {
            Ani subscription = subscriptions.get(id);
            if (subscription == null) {
                continue;
            }
            try {
                // Ownership roots can predate a later path-template change.
                // The current template may therefore have created an empty
                // subscription directory that is not covered by those old
                // roots. The cleanup service still protects every directory
                // used by another live ownership before deleting candidates.
                SubscriptionDirectoryCleanupPolicy.resolveInferredTarget(
                        subscription, downloadPathResolver.resolve(subscription), config)
                        .ifPresent(targets::add);
            } catch (RuntimeException ignored) {
                // A failed path resolution must preserve the directory.
            }
        }
        return List.copyOf(targets);
    }

    private static Map<String, Ani> subscriptionsById(List<Ani> subscriptions) {
        Map<String, Ani> result = new LinkedHashMap<>();
        for (Ani ani : subscriptions) {
            if (ani == null || ani.getId() == null || ani.getId().isBlank()) {
                continue;
            }
            if (result.putIfAbsent(ani.getId(), ani) != null) {
                throw new IllegalStateException("duplicate subscription id: " + ani.getId());
            }
        }
        return result;
    }

    private static List<TorrentsInfo> matchOwnedTasks(
            List<TorrentsInfo> tasks,
            List<DownloadOwnership> ownerships) {
        if (tasks == null || tasks.isEmpty() || ownerships.isEmpty()) {
            return List.of();
        }
        Map<TorrentsInfo, Set<String>> matches = new LinkedHashMap<>();
        for (TorrentsInfo task : tasks) {
            for (DownloadOwnership ownership : ownerships) {
                if (matches(ownership, task)) {
                    matches.computeIfAbsent(task, ignored -> new HashSet<>())
                            .add(ownership.ownershipId());
                }
            }
        }
        if (matches.values().stream().anyMatch(ids -> ids.size() > 1)) {
            throw new IllegalStateException("remote task ownership is ambiguous");
        }
        return List.copyOf(matches.keySet());
    }

    private static boolean matches(DownloadOwnership ownership, TorrentsInfo task) {
        String remoteId = normalize(ownership.remoteTaskId());
        String taskId = normalize(task.getId());
        String infoHash = normalize(ownership.infoHash());
        String taskHash = normalize(task.getHash());
        if (remoteId != null) {
            // A known remote ID is the primary identity. A hash-only match
            // could otherwise remove another client's task for the same torrent.
            if (!remoteId.equals(taskId)) {
                return false;
            }
            return infoHash == null || infoHash.equals(taskHash);
        }
        return infoHash != null && infoHash.equals(taskHash);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isActive(DownloadOwnership ownership) {
        return ownership.state() == OwnershipState.ACTIVE ||
                ownership.state() == OwnershipState.LEGACY_ADOPTED;
    }

    private static boolean isDeletable(DownloadOwnership ownership) {
        return isActive(ownership) || ownership.state() == OwnershipState.PENDING ||
                ownership.state() == OwnershipState.FAILED;
    }

    public record SubscriptionSummary(String id, String title, Integer season) {
    }

    public record DeletionResult(
            int deletedSubscriptions,
            int deletedRemoteTasks,
            int deletedFiles,
            int skippedFiles) {
    }

    interface SubscriptionStore {
        List<Ani> snapshot();

        void commit(List<Ani> candidate);
    }

    interface RemoteTaskGateway {
        boolean supports(String downloaderType);

        List<TorrentsInfo> list();

        void deleteTaskOnly(TorrentsInfo task);
    }

    @FunctionalInterface
    interface SubscriptionDownloadPathResolver {
        String resolve(Ani subscription);
    }

    private static final class AniUtilSubscriptionStore implements SubscriptionStore {
        @Override
        public List<Ani> snapshot() {
            return AniUtil.snapshot();
        }

        @Override
        public void commit(List<Ani> candidate) {
            AniUtil.commit(candidate);
        }
    }

    private static final class TorrentRemoteTaskGateway implements RemoteTaskGateway {
        @Override
        public boolean supports(String downloaderType) {
            return downloaderType != null && downloaderType.equals(ConfigUtil.CONFIG.getDownloadToolType());
        }

        @Override
        public List<TorrentsInfo> list() {
            DownloaderResult<List<TorrentsInfo>> result = TorrentUtil.getTorrentsInfosResult();
            if (!result.isSuccess()) {
                throw new IllegalStateException("list downloader tasks failed: " + result.errorCode());
            }
            return result.value() == null ? List.of() : result.value();
        }

        @Override
        public void deleteTaskOnly(TorrentsInfo task) {
            if (!Boolean.TRUE.equals(TorrentUtil.delete(task, true, false))) {
                throw new IllegalStateException("delete downloader task failed");
            }
        }
    }
}
