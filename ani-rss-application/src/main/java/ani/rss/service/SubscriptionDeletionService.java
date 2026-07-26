package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.download.DownloaderResult;
import ani.rss.entity.Ani;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.ownership.QuarantineService;
import ani.rss.recovery.MissingEpisodeRecoveryService;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.TorrentUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Two-step deletion boundary for subscriptions, remote tasks, and owned files. */
@Service
public final class SubscriptionDeletionService {
    public static final Duration PLAN_TTL = Duration.ofMinutes(10);

    private final OwnershipService ownershipService;
    private final QuarantineService quarantineService;
    private final SubscriptionStore subscriptionStore;
    private final RemoteTaskGateway remoteTasks;
    private final MissingEpisodeRecoveryService recoveryService;
    private final ConcurrentMap<String, PlanState> plans = new ConcurrentHashMap<>();

    @Autowired
    public SubscriptionDeletionService(
            OwnershipService ownershipService,
            QuarantineService quarantineService,
            MissingEpisodeRecoveryService recoveryService) {
        this(ownershipService, quarantineService, new AniUtilSubscriptionStore(), new TorrentRemoteTaskGateway(), recoveryService);
    }

    SubscriptionDeletionService(
            OwnershipService ownershipService,
            QuarantineService quarantineService,
            SubscriptionStore subscriptionStore,
            RemoteTaskGateway remoteTasks) {
        this(ownershipService, quarantineService, subscriptionStore, remoteTasks, null);
    }

    SubscriptionDeletionService(
            OwnershipService ownershipService,
            QuarantineService quarantineService,
            SubscriptionStore subscriptionStore,
            RemoteTaskGateway remoteTasks,
            MissingEpisodeRecoveryService recoveryService) {
        this.ownershipService = Objects.requireNonNull(ownershipService, "ownershipService");
        this.quarantineService = Objects.requireNonNull(quarantineService, "quarantineService");
        this.subscriptionStore = Objects.requireNonNull(subscriptionStore, "subscriptionStore");
        this.remoteTasks = Objects.requireNonNull(remoteTasks, "remoteTasks");
        this.recoveryService = recoveryService;
    }

    public DeletionPlan plan(Collection<String> subscriptionIds, boolean deleteFiles) {
        trimPlans();
        LinkedHashSet<String> ids = normalizedIds(subscriptionIds);
        List<Ani> current = subscriptionStore.snapshot();
        Map<String, Ani> byId = subscriptionsById(current);
        List<Ani> selected = ids.stream()
                .map(id -> {
                    Ani ani = byId.get(id);
                    if (ani == null) {
                        throw new IllegalArgumentException("subscription does not exist: " + id);
                    }
                    return ani;
                })
                .toList();

        List<DownloadOwnership> ownerships = ids.stream()
                .flatMap(id -> ownershipService.listBySubscription(id).stream())
                .filter(SubscriptionDeletionService::isActive)
                .toList();
        String quarantinePlanId = null;
        List<QuarantineService.PlannedFile> files = List.of();
        if (deleteFiles && !ownerships.isEmpty()) {
            for (DownloadOwnership ownership : ownerships) {
                if (!remoteTasks.supports(ownership.downloaderType())) {
                    throw new IllegalStateException(
                            "active downloader cannot safely remove ownership " + ownership.ownershipId());
                }
            }
            QuarantineService.DestructiveOperationPlan quarantinePlan = quarantineService.planOwnerships(
                    ownerships.stream().map(DownloadOwnership::ownershipId).toList());
            quarantinePlanId = quarantinePlan.operationId();
            files = quarantinePlan.files();
        }

        String operationId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + PLAN_TTL.toMillis();
        List<SubscriptionSummary> subscriptions = selected.stream()
                .map(ani -> new SubscriptionSummary(ani.getId(), ani.getTitle(), ani.getSeason()))
                .toList();
        DeletionPlan view = new DeletionPlan(
                operationId,
                createdAt,
                expiresAt,
                deleteFiles,
                subscriptions,
                ownerships.stream().map(DownloadOwnership::ownershipId).toList(),
                files);
        PlanState state = new PlanState(
                view,
                fingerprints(selected),
                List.copyOf(ownerships),
                quarantinePlanId);
        if (plans.putIfAbsent(operationId, state) != null) {
            cancelQuarantine(quarantinePlanId);
            throw new IllegalStateException("operation id collision");
        }
        return view;
    }

    /** Removes subscription metadata only; owned media and remote tasks stay intact. */
    public DeletionResult deleteWithoutFiles(Collection<String> subscriptionIds) {
        DeletionPlan plan = plan(subscriptionIds, false);
        return execute(plan.operationId());
    }

    public DeletionResult execute(String operationId) {
        PlanState plan = requirePlan(operationId);
        synchronized (plan) {
            if (plans.get(operationId) != plan) {
                throw new IllegalArgumentException("subscription deletion plan has already been consumed");
            }
            if (plan.view().expiresAt() <= System.currentTimeMillis()) {
                plans.remove(operationId, plan);
                cancelQuarantine(plan.quarantinePlanId());
                throw new IllegalStateException("subscription deletion plan has expired");
            }

            List<Ani> current = subscriptionStore.snapshot();
            verifySubscriptionsUnchanged(current, plan.subscriptionFingerprints());
            List<TorrentsInfo> tasks = plan.view().deleteFiles()
                    ? remoteTasks.list()
                    : List.of();
            List<TorrentsInfo> ownedTasks = matchOwnedTasks(tasks, plan.ownerships());

            String quarantineOperationId = null;
            try {
                if (plan.quarantinePlanId() != null) {
                    quarantineOperationId = quarantineService.executePlan(plan.quarantinePlanId());
                }
                for (TorrentsInfo task : ownedTasks) {
                    remoteTasks.deleteTaskOnly(task);
                }
                Set<String> ids = plan.subscriptionFingerprints().keySet();
                List<Ani> candidate = current.stream()
                        .filter(ani -> !ids.contains(ani.getId()))
                        .toList();
                subscriptionStore.commit(candidate);
                if (recoveryService != null) {
                    for (String id : ids) {
                        recoveryService.cancelSubscription(id);
                    }
                }
                plans.remove(operationId, plan);
                return new DeletionResult(
                        operationId,
                        quarantineOperationId,
                        ids.size(),
                        ownedTasks.size());
            } catch (Exception failure) {
                if (quarantineOperationId != null) {
                    try {
                        quarantineService.restore(quarantineOperationId);
                    } catch (Exception restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
                throw failure instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException("subscription deletion failed", failure);
            }
        }
    }

    public void cancel(String operationId) {
        PlanState plan = plans.remove(operationId);
        if (plan == null) {
            throw new IllegalArgumentException("subscription deletion plan does not exist");
        }
        cancelQuarantine(plan.quarantinePlanId());
    }

    private PlanState requirePlan(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operation id is required");
        }
        PlanState plan = plans.get(operationId);
        if (plan == null) {
            throw new IllegalArgumentException("subscription deletion plan does not exist");
        }
        return plan;
    }

    private void trimPlans() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PlanState> entry : plans.entrySet()) {
            if (entry.getValue().view().expiresAt() <= now && plans.remove(entry.getKey(), entry.getValue())) {
                cancelQuarantine(entry.getValue().quarantinePlanId());
            }
        }
    }

    private void cancelQuarantine(String operationId) {
        if (operationId == null) {
            return;
        }
        try {
            quarantineService.cancelPlan(operationId);
        } catch (IllegalArgumentException ignored) {
            // The nested plan may already have expired or been consumed.
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

    private static Map<String, String> fingerprints(List<Ani> subscriptions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Ani ani : subscriptions) {
            result.put(ani.getId(), GsonStatic.toJson(ani));
        }
        return Map.copyOf(result);
    }

    private static void verifySubscriptionsUnchanged(
            List<Ani> current,
            Map<String, String> fingerprints) {
        Map<String, Ani> currentById = subscriptionsById(current);
        for (Map.Entry<String, String> entry : fingerprints.entrySet()) {
            Ani ani = currentById.get(entry.getKey());
            if (ani == null || !entry.getValue().equals(GsonStatic.toJson(ani))) {
                throw new IllegalStateException("subscription changed after deletion plan was created: " + entry.getKey());
            }
        }
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
        if (remoteId != null && remoteId.equals(taskId)) {
            return true;
        }
        String infoHash = normalize(ownership.infoHash());
        String taskHash = normalize(task.getHash());
        return infoHash != null && infoHash.equals(taskHash);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isActive(DownloadOwnership ownership) {
        return ownership.state() == OwnershipState.ACTIVE ||
                ownership.state() == OwnershipState.LEGACY_ADOPTED;
    }

    public record DeletionPlan(
            String operationId,
            long createdAt,
            long expiresAt,
            boolean deleteFiles,
            List<SubscriptionSummary> subscriptions,
            List<String> ownershipIds,
            List<QuarantineService.PlannedFile> files) {
        public DeletionPlan {
            subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
            ownershipIds = ownershipIds == null ? List.of() : List.copyOf(ownershipIds);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record SubscriptionSummary(String id, String title, Integer season) {
    }

    public record DeletionResult(
            String operationId,
            String quarantineOperationId,
            int deletedSubscriptions,
            int deletedRemoteTasks) {
    }

    private record PlanState(
            DeletionPlan view,
            Map<String, String> subscriptionFingerprints,
            List<DownloadOwnership> ownerships,
            String quarantinePlanId) {
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
