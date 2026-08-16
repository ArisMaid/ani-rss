package ani.rss.recovery;

import ani.rss.commons.GsonStatic;
import ani.rss.commons.FileUtils;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.StandbyRss;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.ItemsUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconciles only RSS items ANI-RSS has accepted as expected downloads. It is
 * intentionally independent from sequence-gap/omit notifications.
 */
@Slf4j
@Service
public class MissingEpisodeRecoveryService {
    static final Duration INITIAL_BACKOFF = Duration.ofMinutes(15);
    static final Duration MAX_BACKOFF = Duration.ofHours(6);
    private static final Duration SATISFIED_AUDIT_INTERVAL = Duration.ofHours(6);

    private static final Set<TorrentsStateEnum> TERMINAL_STATES = Set.of(
            TorrentsStateEnum.queuedUP,
            TorrentsStateEnum.uploading,
            TorrentsStateEnum.stalledUP,
            TorrentsStateEnum.stoppedUP,
            TorrentsStateEnum.pausedUP,
            TorrentsStateEnum.checkingUP);

    private final RecoveryRepository repository;
    private final OwnershipService ownershipService;
    private final ObjectProvider<DownloadService> downloadService;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public enum SubmissionDisposition {
        NEW,
        TRACKED,
        LEGACY_CACHE_SATISFIED
    }

    public MissingEpisodeRecoveryService(
            RecoveryRepository repository,
            OwnershipService ownershipService,
            ObjectProvider<DownloadService> downloadService) {
        this.repository = repository;
        this.ownershipService = ownershipService;
        this.downloadService = downloadService;
    }

    /** Records an item once it has passed the regular subscription policies. */
    public void observeEligible(Ani ani, Item item) {
        if (ani == null || item == null || StrUtil.isBlank(item.getInfoHash())) {
            return;
        }
        repository.observe(ani, item);
    }

    /**
     * Registers an eligible item and decides who owns any follow-up submission.
     * Existing records are always reconciled through the durable recovery state
     * machine. A cached input with no prior record predates that state machine,
     * so preserve the legacy "already handled" meaning during upgrades and
     * restores instead of redownloading an entire historical feed.
     */
    public SubmissionDisposition prepareEligible(Ani ani, Item item, boolean cachedInput) {
        return prepareEligible(ani, item, cachedInput, null);
    }

    public SubmissionDisposition prepareEligible(
            Ani ani, Item item, boolean cachedInput, String saveRoot) {
        if (!valid(ani, item)) {
            return SubmissionDisposition.NEW;
        }
        RecoveryRepository.Observation observation = repository.observeWithStatus(ani, item);
        if (!observation.created()) {
            RecoveryRecord current = observation.record();
            if (current.state() == RecoveryState.SUPERSEDED && recoveryEnabled()) {
                Optional<RecoveryRecord> equivalent = findHealthyEquivalent(
                        ani, item, current, saveRoot, ConfigUtil.downloadToolType(),
                        repository.listBySubscription(ani.getId()));
                if (equivalent.isEmpty()) {
                    repository.reactivate(current.subscriptionId(), current.sourceHash());
                }
            }
            return SubmissionDisposition.TRACKED;
        }
        if (cachedInput) {
            repository.markSatisfied(ani.getId(), item.getInfoHash());
            return SubmissionDisposition.LEGACY_CACHE_SATISFIED;
        }
        if (recoveryEnabled()) {
            Optional<RecoveryRecord> equivalent = findHealthyEquivalent(
                    ani, item, observation.record(), saveRoot, ConfigUtil.downloadToolType(),
                    repository.listBySubscription(ani.getId()));
            if (equivalent.isPresent()) {
                repository.markSuperseded(ani.getId(), item.getInfoHash(),
                        "RECOVERY_EQUIVALENT_PRESENT");
                log.info("同版本媒体已存在，跳过新 hash subscriptionId:{} hash:{} existingHash:{}",
                        ani.getId(), shortHash(item.getInfoHash()), shortHash(equivalent.get().infoHash()));
                return SubmissionDisposition.TRACKED;
            }
        }
        return SubmissionDisposition.NEW;
    }

    /** Promotes a verified torrent identity while preserving the RSS cache key. */
    public void promoteCanonicalHash(Ani ani, Item item, String canonicalHash) {
        if (!valid(ani, item) || StrUtil.isBlank(canonicalHash)) {
            return;
        }
        repository.promoteCanonicalHash(ani.getId(), item.getInfoHash(), canonicalHash);
    }

    public void markRemoteObserved(Ani ani, Item item) {
        if (!valid(ani, item)) {
            return;
        }
        repository.markSubmitted(ani.getId(), item.getInfoHash(),
                System.currentTimeMillis() + INITIAL_BACKOFF.toMillis());
    }

    public void markLocalSatisfied(Ani ani, Item item) {
        if (valid(ani, item)) {
            repository.markSatisfied(ani.getId(), item.getInfoHash());
        }
    }

    /** Holds an accepted item until its normal delayed-download deadline. */
    public void deferUntil(Ani ani, Item item, long nextAttemptAt) {
        if (valid(ani, item)) {
            repository.defer(ani.getId(), item.getInfoHash(), nextAttemptAt);
        }
    }

    public void markSubmissionResult(Ani ani, Item item, boolean success, String errorCode) {
        if (!valid(ani, item)) {
            return;
        }
        if (success) {
            if (!repository.markSubmitted(ani.getId(), item.getInfoHash(),
                    System.currentTimeMillis() + INITIAL_BACKOFF.toMillis())) {
                log.warn("recovery record not updated after submission subscriptionId:{} hash:{}",
                        ani.getId(), shortHash(item.getInfoHash()));
            }
            return;
        }
        repository.find(ani.getId(), item.getInfoHash())
                .ifPresent(record -> scheduleRetry(record,
                        StrUtil.blankToDefault(errorCode, "DOWNLOAD_SUBMISSION_FAILED")));
    }

    /**
     * Runs after a normal RSS pass. It never derives a candidate from episode
     * numbering; records are created only from accepted RSS entries.
     */
    public void reconcile(Ani ani, List<TorrentsInfo> observedTasks) {
        reconcile(ani, List.of(), observedTasks, activeDownloadCount(observedTasks));
    }

    public void reconcile(Ani ani, List<Item> eligibleItems, List<TorrentsInfo> observedTasks) {
        reconcile(ani, eligibleItems, observedTasks, activeDownloadCount(observedTasks));
    }

    public void reconcile(
            Ani ani,
            List<Item> eligibleItems,
            List<TorrentsInfo> observedTasks,
            long activeDownloads) {
        if (ani == null || !Boolean.TRUE.equals(ani.getEnable())) {
            return;
        }
        DownloaderClient client = TorrentUtil.client();
        if (client == null) {
            return;
        }
        String downloaderType = client.configurationSnapshot().getDownloadToolType();
        boolean recoveryEnabled = recoveryEnabled();
        coordinateCandidates(ani, eligibleItems, downloaderType, recoveryEnabled, currentSaveRoot(ani));
        Map<String, TorrentsInfo> tasks = tasksByHash(observedTasks);
        RecoverySubmissionBudget submissionBudget = new RecoverySubmissionBudget(activeDownloads);
        long now = System.currentTimeMillis();
        long satisfiedAuditBefore = now - SATISFIED_AUDIT_INTERVAL.toMillis();
        for (RecoveryRecord record : repository.listForReconciliation(
                ani.getId(), satisfiedAuditBefore)) {
            Item item = item(record);
            if (item == null || StrUtil.isBlank(item.getInfoHash())) {
                scheduleRetry(record, "RECOVERY_ITEM_UNAVAILABLE");
                continue;
            }
            if (isExplicitlyExcluded(ani, item)) {
                repository.cancel(record.subscriptionId(), record.infoHash());
                continue;
            }
            if (!recoveryEnabled && record.state() != RecoveryState.DEFERRED) {
                continue;
            }

            Optional<DownloadOwnership> ownershipOpt = ownershipService
                    .findManagedByInfoHash(downloaderType, record.infoHash());
            DownloadOwnership ownership = ownershipOpt.orElse(null);
            if (ownership != null && ownership.state() == OwnershipState.QUARANTINED) {
                // Quarantine is an intentional local removal with a restore path.
                touchSatisfiedAudit(record);
                continue;
            }

            TorrentsInfo task = tasks.get(normalize(record.infoHash()));
            Optional<DownloadOwnership> ownedTask = ownershipService.findRecoverableOwnedTask(
                    downloaderType, record.subscriptionId(), record.infoHash(), task);
            if (task != null && ownedTask.isEmpty()) {
                // An observed task is never authority by hash alone. Keep the
                // recovery record intact, but do not inspect or modify it.
                task = null;
            }
            if (task != null && isInProgress(task)) {
                repository.markSubmitted(record.subscriptionId(), record.infoHash(),
                        now + INITIAL_BACKOFF.toMillis());
                continue;
            }

            if (ownership != null) {
                OwnershipService.MediaVerification verification = ownershipService.verifyMediaFiles(ownership);
                if (verification.manifestAvailable() && verification.healthy()) {
                    if (record.state() == RecoveryState.SATISFIED) {
                        repository.touchSatisfiedAudit(record.recoveryId());
                    } else {
                        repository.markSatisfied(record.subscriptionId(), record.infoHash());
                    }
                    continue;
                }
                if (verification.manifestAvailable() && record.state() == RecoveryState.SATISFIED) {
                    // A previously verified file later disappeared or changed.
                    // Re-arm once, then normal retry backoff governs failures.
                    repository.armMissing(record.subscriptionId(), record.infoHash());
                    record = repository.find(record.subscriptionId(), record.infoHash()).orElse(record);
                }
                if (task != null && verification.manifestAvailable()) {
                    if (due(record, now)) {
                        recoverExistingTask(record, ani, item, task, client, submissionBudget);
                    }
                    continue;
                }
                if (!verification.manifestAvailable() && "OpenList".equalsIgnoreCase(downloaderType)) {
                    reconcileOpenList(record, ani, item, ownership, submissionBudget);
                    continue;
                }
                if (verification.manifestAvailable() || ownership.state() == OwnershipState.FAILED ||
                        ownership.state() == OwnershipState.PENDING) {
                    if (due(record, now)) {
                        requeue(record, ani, item, submissionBudget);
                    }
                    continue;
                }
            }

            // A legacy local-file match has no ownership manifest. Do not turn
            // a previously satisfied, unowned item into an automatic download.
            if (record.state() == RecoveryState.SATISFIED) {
                touchSatisfiedAudit(record);
            } else if (due(record, now)) {
                requeue(record, ani, item, submissionBudget);
            }
        }
    }

    void coordinateCandidates(
            Ani ani,
            List<Item> eligibleItems,
            String downloaderType,
            boolean recoveryEnabled,
            String saveRoot) {
        List<RecoveryRecord> records = repository.listBySubscription(ani.getId());
        if (records.isEmpty()) {
            return;
        }
        Config config = ConfigUtil.snapshot();
        List<IndexedRecord> indexedRecords = new ArrayList<>();
        Map<String, IndexedRecord> recordsBySource = new HashMap<>();
        Map<String, List<IndexedRecord>> recordsByRelease = new HashMap<>();
        for (RecoveryRecord record : records) {
            Item recordItem = item(record);
            if (recordItem == null) {
                continue;
            }
            RecoveryItemIdentity.Value identity = RecoveryItemIdentity.from(ani, recordItem);
            IndexedRecord indexed = new IndexedRecord(record, recordItem, identity);
            indexedRecords.add(indexed);
            recordsBySource.put(normalize(record.sourceHash()), indexed);
            if (identity.named()) {
                recordsByRelease.computeIfAbsent(identity.releaseKey(), ignored -> new ArrayList<>())
                        .add(indexed);
            }
        }

        List<SelectedCandidate> selected = new ArrayList<>();
        Set<String> selectedSources = new HashSet<>();
        Map<String, List<SelectedCandidate>> selectedByEpisode = new LinkedHashMap<>();
        Map<String, List<SelectedCandidate>> selectedByRelease = new LinkedHashMap<>();
        for (Item item : eligibleItems == null ? List.<Item>of() : eligibleItems) {
            if (item == null || StrUtil.isBlank(item.getInfoHash())) {
                continue;
            }
            IndexedRecord indexed = recordsBySource.get(normalize(item.getInfoHash()));
            if (indexed == null || indexed.record().state() == RecoveryState.CANCELLED) {
                continue;
            }
            SelectedCandidate candidate = new SelectedCandidate(
                    item, indexed.record(), RecoveryItemIdentity.from(ani, item));
            selected.add(candidate);
            selectedSources.add(normalize(indexed.record().sourceHash()));
            selectedByEpisode.computeIfAbsent(candidate.identity().episodeKey(), ignored -> new ArrayList<>())
                    .add(candidate);
            String releaseKey = candidate.identity().named()
                    ? candidate.identity().releaseKey()
                    : "record:" + candidate.record().recoveryId();
            selectedByRelease.computeIfAbsent(releaseKey, ignored -> new ArrayList<>()).add(candidate);
        }

        Set<String> allowedSources = new HashSet<>();
        Set<String> releaseHandled = new HashSet<>();
        for (List<SelectedCandidate> releaseCandidates : selectedByRelease.values()) {
            SelectedCandidate selectedRelease = releaseCandidates.get(0);
            List<IndexedRecord> equivalentRecords = selectedRelease.identity().named()
                    ? recordsByRelease.getOrDefault(selectedRelease.identity().releaseKey(), List.of())
                    : List.of();
            RecoveryRecord keeper = selectKeeper(
                    releaseCandidates, equivalentRecords, downloaderType, saveRoot);
            allowedSources.add(normalize(keeper.sourceHash()));
            if (recoveryEnabled && keeper.state() == RecoveryState.SUPERSEDED) {
                repository.reactivate(keeper.subscriptionId(), keeper.sourceHash());
            }

            boolean keeperIsCurrent = selectedSources.contains(normalize(keeper.sourceHash()));
            for (IndexedRecord duplicate : equivalentRecords) {
                RecoveryRecord duplicateRecord = duplicate.record();
                if (duplicateRecord.recoveryId().equals(keeper.recoveryId()) ||
                        duplicateRecord.state() == RecoveryState.CANCELLED) {
                    continue;
                }
                String reason = keeperIsCurrent
                        ? "RECOVERY_DUPLICATE_RELEASE"
                        : "RECOVERY_EQUIVALENT_PRESENT";
                supersede(duplicateRecord, reason);
                releaseHandled.add(duplicateRecord.recoveryId());
            }
        }

        boolean coexist = Boolean.TRUE.equals(config.getCoexist());
        boolean rename = Boolean.TRUE.equals(config.getRename());
        boolean downloadNew = Boolean.TRUE.equals(ani.getDownloadNew());
        boolean skipHalfEpisode = Boolean.TRUE.equals(config.getSkip5());
        for (IndexedRecord indexed : indexedRecords) {
            RecoveryRecord record = indexed.record();
            if (record.state() == RecoveryState.CANCELLED ||
                    releaseHandled.contains(record.recoveryId()) ||
                    allowedSources.contains(normalize(record.sourceHash()))) {
                continue;
            }
            Item recordItem = indexed.item();
            if (!sourceConfigured(ani, recordItem, config)) {
                supersede(record, "RECOVERY_SOURCE_DISABLED");
                continue;
            }
            if (skipHalfEpisode && ItemsUtil.is5(recordItem)) {
                supersede(record, "RECOVERY_HALF_EPISODE_DISABLED");
                continue;
            }
            if (selectedSources.contains(normalize(record.sourceHash()))) {
                continue;
            }
            if (downloadNew && !selected.isEmpty()) {
                supersede(record, "RECOVERY_DOWNLOAD_NEW_POLICY");
                continue;
            }
            RecoveryItemIdentity.Value identity = indexed.identity();
            List<SelectedCandidate> episodeCandidates = selectedByEpisode.get(identity.episodeKey());
            if (episodeCandidates == null || episodeCandidates.isEmpty()) {
                continue;
            }
            if (!coexist) {
                supersede(record, "RECOVERY_CURRENT_RSS_REPLACED");
                continue;
            }
            boolean sameFingerprint = episodeCandidates.stream()
                    .anyMatch(candidate -> candidate.identity().sameRelease(identity));
            boolean outputCollision = rename && episodeCandidates.stream()
                    .anyMatch(candidate -> candidate.identity().sameOutput(identity));
            if (sameFingerprint || outputCollision) {
                supersede(record, outputCollision
                        ? "RECOVERY_OUTPUT_NAME_COLLISION"
                        : "RECOVERY_DUPLICATE_RELEASE");
            }
        }
    }

    private RecoveryRecord selectKeeper(
            List<SelectedCandidate> selected,
            List<IndexedRecord> equivalentRecords,
            String downloaderType,
            String saveRoot) {
        for (SelectedCandidate candidate : selected) {
            if (hasManagedOwnership(candidate.record(), downloaderType)) {
                return candidate.record();
            }
        }
        for (IndexedRecord equivalent : equivalentRecords) {
            RecoveryRecord record = equivalent.record();
            if (record.state() != RecoveryState.CANCELLED &&
                    healthyOwnedMedia(record, downloaderType, saveRoot)) {
                return record;
            }
        }
        return selected.get(0).record();
    }

    private Optional<RecoveryRecord> findHealthyEquivalent(
            Ani ani,
            Item desiredItem,
            RecoveryRecord current,
            String saveRoot,
            String downloaderType,
            List<RecoveryRecord> records) {
        if (StrUtil.isBlank(saveRoot) || StrUtil.isBlank(downloaderType)) {
            return Optional.empty();
        }
        RecoveryItemIdentity.Value desired = RecoveryItemIdentity.from(ani, desiredItem);
        return records.stream()
                .filter(record -> !record.recoveryId().equals(current.recoveryId()))
                .filter(record -> record.state() != RecoveryState.CANCELLED)
                .filter(record -> {
                    Item candidate = item(record);
                    return candidate != null && desired.sameRelease(RecoveryItemIdentity.from(ani, candidate));
                })
                .filter(record -> healthyOwnedMedia(record, downloaderType, saveRoot))
                .findFirst();
    }

    private boolean healthyOwnedMedia(RecoveryRecord record, String downloaderType, String saveRoot) {
        return ownershipService.findManagedByInfoHash(downloaderType, record.infoHash())
                .filter(ownership -> record.subscriptionId().equals(ownership.subscriptionId()))
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED)
                .filter(ownership -> sameRoot(ownership.saveRoot(), saveRoot))
                .filter(ownership -> ownershipService.listFiles(ownership.ownershipId()).stream()
                        .anyMatch(file -> Boolean.TRUE.equals(FileUtils.isVideoFormat(file.relativePath()))))
                .filter(ownership -> ownershipService.verifyMediaFiles(ownership).healthy())
                .isPresent();
    }

    private boolean hasManagedOwnership(RecoveryRecord record, String downloaderType) {
        return ownershipService.findManagedByInfoHash(downloaderType, record.infoHash())
                .filter(ownership -> record.subscriptionId().equals(ownership.subscriptionId()))
                .filter(ownership -> ownership.state() != OwnershipState.FAILED &&
                        ownership.state() != OwnershipState.QUARANTINED)
                .isPresent();
    }

    private String currentSaveRoot(Ani ani) {
        DownloadService service = downloadService.getIfAvailable();
        if (service == null) {
            return null;
        }
        try {
            return service.getDownloadPath(ani);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean sameRoot(String first, String second) {
        if (StrUtil.isBlank(first) || StrUtil.isBlank(second)) {
            return false;
        }
        try {
            return Objects.equals(FileUtils.getAbsolutePath(first), FileUtils.getAbsolutePath(second));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean sourceConfigured(Ani ani, Item item, Config config) {
        if (!Boolean.FALSE.equals(item.getMaster())) {
            return true;
        }
        if (!Boolean.TRUE.equals(config.getStandbyRss()) || ani.getStandbyRssList() == null) {
            return false;
        }
        String subgroup = RecoveryItemIdentity.normalize(item.getSubgroup());
        return ani.getStandbyRssList().stream()
                .filter(Objects::nonNull)
                .map(StandbyRss::getLabel)
                .map(RecoveryItemIdentity::normalize)
                .anyMatch(subgroup::equals);
    }

    private void supersede(RecoveryRecord record, String reason) {
        if (record.state() == RecoveryState.SUPERSEDED && reason.equals(record.lastErrorCode())) {
            return;
        }
        repository.markSuperseded(record.subscriptionId(), record.sourceHash(), reason);
        log.info("停止旧版本补下载 subscriptionId:{} hash:{} reason:{}",
                record.subscriptionId(), shortHash(record.infoHash()), reason);
    }

    private static boolean recoveryEnabled() {
        return !Boolean.FALSE.equals(ConfigUtil.snapshot().getMissingEpisodeRecoveryEnabled());
    }

    private record SelectedCandidate(
            Item item, RecoveryRecord record, RecoveryItemIdentity.Value identity) {
    }

    private record IndexedRecord(
            RecoveryRecord record, Item item, RecoveryItemIdentity.Value identity) {
    }

    public void cancelSubscription(String subscriptionId) {
        repository.cancelSubscription(subscriptionId);
    }

    public void cancel(String subscriptionId, String infoHash) {
        repository.cancel(subscriptionId, infoHash);
    }

    public boolean hasOutstanding(String subscriptionId) {
        return repository.hasOutstanding(subscriptionId);
    }

    private void reconcileOpenList(
            RecoveryRecord record, Ani ani, Item item, DownloadOwnership ownership,
            RecoverySubmissionBudget submissionBudget) {
        DownloaderClient client = TorrentUtil.client();
        if (client == null || !(client.adapter() instanceof OpenList openList)) {
            scheduleRetry(record, "OPENLIST_RECOVERY_CLIENT_UNAVAILABLE");
            return;
        }
        Optional<Boolean> present = openList.hasExpectedMedia(ownership.saveRoot(), item.getReName());
        if (present.isEmpty()) {
            scheduleRetry(record, "OPENLIST_TARGET_UNVERIFIABLE");
            return;
        }
        if (present.get()) {
            if (record.state() == RecoveryState.SATISFIED) {
                repository.touchSatisfiedAudit(record.recoveryId());
            } else {
                repository.markSatisfied(record.subscriptionId(), record.infoHash());
            }
            return;
        }
        if (due(record, System.currentTimeMillis())) {
            requeue(record, ani, item, submissionBudget);
        }
    }

    private void recoverExistingTask(
            RecoveryRecord record, Ani ani, Item item, TorrentsInfo task,
            DownloaderClient client, RecoverySubmissionBudget submissionBudget) {
        String key = key(record);
        if (!inFlight.add(key)) {
            return;
        }
        try {
            DownloaderResult<Void> result = client.recover(task);
            if (result.isSuccess()) {
                repository.markSubmitted(record.subscriptionId(), record.infoHash(),
                        System.currentTimeMillis() + INITIAL_BACKOFF.toMillis());
                log.info("已请求缺失媒体校验 subscriptionId:{} hash:{}", record.subscriptionId(), shortHash(record.infoHash()));
                return;
            }
            if ("Aria2".equalsIgnoreCase(client.configurationSnapshot().getDownloadToolType())) {
                DownloaderResult<Void> removed = client.delete(task, false);
                if (removed.isSuccess()) {
                    inFlight.remove(key);
                    requeue(record, ani, item, submissionBudget);
                    return;
                }
                scheduleRetry(record, removed.errorCode());
                return;
            }
            scheduleRetry(record, result.errorCode());
        } finally {
            inFlight.remove(key);
        }
    }

    private void requeue(
            RecoveryRecord record,
            Ani ani,
            Item item,
            RecoverySubmissionBudget submissionBudget) {
        String key = key(record);
        if (!inFlight.add(key)) {
            return;
        }
        try {
            if (!submissionBudget.hasCapacity()) {
                return;
            }
            DownloadService service = downloadService.getIfAvailable();
            if (service == null) {
                scheduleRetry(record, "RECOVERY_DOWNLOAD_SERVICE_UNAVAILABLE");
                return;
            }
            if (service.itemDownloaded(ani, item, false)) {
                repository.markSatisfied(record.subscriptionId(), record.infoHash());
                return;
            }
            File input = TorrentUtil.getTorrent(ani, item);
            if (!input.exists()) {
                input = TorrentUtil.saveTorrent(ani, item);
            }
            if (!input.exists()) {
                scheduleRetry(record, "RECOVERY_INPUT_UNAVAILABLE");
                return;
            }
            boolean accepted = service.recoverDownload(ani, item, service.getDownloadPath(ani), input);
            String errorCode = accepted ? null : "RECOVERY_DOWNLOAD_REJECTED";
            markSubmissionResult(ani, item, accepted, errorCode);
            if (accepted) {
                submissionBudget.recordSubmission();
            }
            notifyRecoveryTransition(record, ani, item, accepted, errorCode);
            if (accepted) {
                log.info("已重新提交缺失集 subscriptionId:{} hash:{}", record.subscriptionId(), shortHash(record.infoHash()));
            }
        } catch (Exception e) {
            scheduleRetry(record, "RECOVERY_REQUEUE_FAILED");
            log.warn("缺失集补下载失败 subscriptionId:{} type:{}", record.subscriptionId(),
                    e.getClass().getSimpleName());
        } finally {
            inFlight.remove(key);
        }
    }

    private static boolean due(RecoveryRecord record, long now) {
        return record.nextAttemptAt() <= now;
    }

    private static long activeDownloadCount(List<TorrentsInfo> tasks) {
        return (tasks == null ? List.<TorrentsInfo>of() : tasks).stream()
                .filter(Objects::nonNull)
                .filter(task -> !task.finished())
                .count();
    }

    private static final class RecoverySubmissionBudget {
        private final int limit;
        private long activeDownloads;

        private RecoverySubmissionBudget(long activeDownloads) {
            Integer configuredLimit = ConfigUtil.snapshot().getDownloadCount();
            this.limit = configuredLimit == null ? 0 : configuredLimit;
            this.activeDownloads = Math.max(0L, activeDownloads);
        }

        private boolean hasCapacity() {
            return limit < 1 || activeDownloads < limit;
        }

        private void recordSubmission() {
            activeDownloads++;
        }
    }

    private void touchSatisfiedAudit(RecoveryRecord record) {
        if (record.state() == RecoveryState.SATISFIED) {
            repository.touchSatisfiedAudit(record.recoveryId());
        }
    }

    private static boolean isTerminal(TorrentsInfo task) {
        return task != null && task.getState() != null && TERMINAL_STATES.contains(task.getState());
    }

    private static boolean isInProgress(TorrentsInfo task) {
        if (task == null || task.getState() == null) {
            return true;
        }
        return task.getState() != TorrentsStateEnum.missingFiles && task.getState() != TorrentsStateEnum.error &&
                !isTerminal(task);
    }

    private static Map<String, TorrentsInfo> tasksByHash(List<TorrentsInfo> tasks) {
        Map<String, TorrentsInfo> result = new HashMap<>();
        if (tasks == null) {
            return result;
        }
        for (TorrentsInfo task : tasks) {
            if (task != null && StrUtil.isNotBlank(task.getHash())) {
                result.putIfAbsent(normalize(task.getHash()), task);
            }
        }
        return result;
    }

    private static Item item(RecoveryRecord record) {
        try {
            Item item = GsonStatic.fromJson(record.itemJson(), Item.class);
            if (item != null) {
                // The RSS value names the cached input. The canonical hash is
                // intentionally kept only on the record for task ownership.
                item.setInfoHash(record.sourceHash());
            }
            return item;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isExplicitlyExcluded(Ani ani, Item item) {
        if (!Boolean.TRUE.equals(ani.getEnable())) {
            return true;
        }
        if (item.getEpisode() != null && ani.getNotDownload() != null && ani.getNotDownload().contains(item.getEpisode())) {
            return true;
        }
        Config config = ConfigUtil.snapshot();
        return !ItemsUtil.isAllowedByDownloadRules(ani, item, config);
    }

    private void scheduleRetry(RecoveryRecord record, String errorCode) {
        int attempt = Math.max(1, record.attempts() + 1);
        long factor = 1L << Math.min(5, attempt - 1);
        long delay = Math.min(MAX_BACKOFF.toMillis(), INITIAL_BACKOFF.toMillis() * factor);
        repository.scheduleRetry(record.subscriptionId(), record.infoHash(),
                StrUtil.blankToDefault(errorCode, "RECOVERY_FAILED"), System.currentTimeMillis() + delay);
    }

    private static void notifyRecoveryTransition(
            RecoveryRecord before, Ani ani, Item item, boolean accepted, String errorCode) {
        try {
            if (accepted) {
                if (before.state() != RecoveryState.SUBMITTED) {
                    NotificationUtil.send(
                            ConfigUtil.CONFIG, ani, item.getReName(), NotificationStatusEnum.DOWNLOAD_START);
                }
                return;
            }
            String code = StrUtil.blankToDefault(errorCode, "RECOVERY_DOWNLOAD_REJECTED");
            if (before.state() != RecoveryState.RETRY_WAIT || !code.equals(before.lastErrorCode())) {
                NotificationUtil.send(ConfigUtil.CONFIG, ani, item.getReName(), NotificationStatusEnum.ERROR);
            }
        } catch (RuntimeException notificationFailure) {
            log.warn("补下载通知失败 subscriptionId:{} type:{}", before.subscriptionId(),
                    notificationFailure.getClass().getSimpleName());
        }
    }

    private static boolean valid(Ani ani, Item item) {
        return ani != null && StrUtil.isNotBlank(ani.getId()) && item != null && StrUtil.isNotBlank(item.getInfoHash());
    }

    private static String key(RecoveryRecord record) {
        return record.subscriptionId() + "\u0000" + normalize(record.infoHash());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String shortHash(String value) {
        String normalized = normalize(value);
        return normalized.length() <= 12 ? normalized : normalized.substring(0, 12);
    }
}
