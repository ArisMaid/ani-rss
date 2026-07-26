package ani.rss.recovery;

import ani.rss.commons.GsonStatic;
import ani.rss.commons.RegexRuleMatcher;
import ani.rss.download.DownloaderClient;
import ani.rss.download.DownloaderResult;
import ani.rss.download.OpenList;
import ani.rss.entity.Ani;
import ani.rss.entity.Config;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.enums.NotificationStatusEnum;
import ani.rss.enums.TorrentsStateEnum;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.OwnershipState;
import ani.rss.service.DownloadService;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.NotificationUtil;
import ani.rss.util.other.TorrentUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        if (!valid(ani, item)) {
            return SubmissionDisposition.NEW;
        }
        boolean tracked = repository.find(ani.getId(), item.getInfoHash()).isPresent();
        repository.observe(ani, item);
        if (tracked) {
            return SubmissionDisposition.TRACKED;
        }
        if (cachedInput) {
            repository.markSatisfied(ani.getId(), item.getInfoHash());
            return SubmissionDisposition.LEGACY_CACHE_SATISFIED;
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
        if (ani == null || !Boolean.TRUE.equals(ani.getEnable())) {
            return;
        }
        DownloaderClient client = TorrentUtil.client();
        if (client == null) {
            return;
        }
        String downloaderType = client.configurationSnapshot().getDownloadToolType();
        Map<String, TorrentsInfo> tasks = tasksByHash(observedTasks);
        long now = System.currentTimeMillis();
        for (RecoveryRecord record : repository.listRecoverable(ani.getId())) {
            Item item = item(record);
            if (item == null || StrUtil.isBlank(item.getInfoHash())) {
                scheduleRetry(record, "RECOVERY_ITEM_UNAVAILABLE");
                continue;
            }
            if (isExplicitlyExcluded(ani, item)) {
                repository.cancel(record.subscriptionId(), record.infoHash());
                continue;
            }

            Optional<DownloadOwnership> ownershipOpt = ownershipService
                    .findManagedByInfoHash(downloaderType, record.infoHash());
            DownloadOwnership ownership = ownershipOpt.orElse(null);
            if (ownership != null && ownership.state() == OwnershipState.QUARANTINED) {
                // Quarantine is an intentional local removal with a restore path.
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
                    repository.markSatisfied(record.subscriptionId(), record.infoHash());
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
                        recoverExistingTask(record, ani, item, task, observedTasks, client);
                    }
                    continue;
                }
                if (!verification.manifestAvailable() && "OpenList".equalsIgnoreCase(downloaderType)) {
                    reconcileOpenList(record, ani, item, ownership, observedTasks);
                    continue;
                }
                if (verification.manifestAvailable() || ownership.state() == OwnershipState.FAILED ||
                        ownership.state() == OwnershipState.PENDING) {
                    if (due(record, now)) {
                        requeue(record, ani, item, observedTasks);
                    }
                    continue;
                }
            }

            // A legacy local-file match has no ownership manifest. Do not turn
            // a previously satisfied, unowned item into an automatic download.
            if (record.state() != RecoveryState.SATISFIED && due(record, now)) {
                requeue(record, ani, item, observedTasks);
            }
        }
    }

    public void cancelSubscription(String subscriptionId) {
        repository.cancelSubscription(subscriptionId);
    }

    public void cancel(String subscriptionId, String infoHash) {
        repository.cancel(subscriptionId, infoHash);
    }

    public boolean hasOutstanding(String subscriptionId) {
        return repository.listRecoverable(subscriptionId).stream()
                .anyMatch(record -> record.state() != RecoveryState.SATISFIED);
    }

    private void reconcileOpenList(
            RecoveryRecord record, Ani ani, Item item, DownloadOwnership ownership,
            List<TorrentsInfo> observedTasks) {
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
            repository.markSatisfied(record.subscriptionId(), record.infoHash());
            return;
        }
        if (due(record, System.currentTimeMillis())) {
            requeue(record, ani, item, observedTasks);
        }
    }

    private void recoverExistingTask(
            RecoveryRecord record, Ani ani, Item item, TorrentsInfo task,
            List<TorrentsInfo> observedTasks, DownloaderClient client) {
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
                    requeue(record, ani, item, observedTasks);
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

    private void requeue(RecoveryRecord record, Ani ani, Item item, List<TorrentsInfo> observedTasks) {
        String key = key(record);
        if (!inFlight.add(key)) {
            return;
        }
        try {
            if (!hasDownloadCapacity(observedTasks)) {
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
            DownloadService service = downloadService.getIfAvailable();
            if (service == null) {
                scheduleRetry(record, "RECOVERY_DOWNLOAD_SERVICE_UNAVAILABLE");
                return;
            }
            boolean accepted = service.recoverDownload(ani, item, service.getDownloadPath(ani), input);
            String errorCode = accepted ? null : "RECOVERY_DOWNLOAD_REJECTED";
            markSubmissionResult(ani, item, accepted, errorCode);
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

    private boolean hasDownloadCapacity(List<TorrentsInfo> tasks) {
        Config config = ConfigUtil.snapshot();
        int limit = config.getDownloadCount() == null ? 0 : config.getDownloadCount();
        if (limit < 1) {
            return true;
        }
        long active = (tasks == null ? List.<TorrentsInfo>of() : tasks).stream()
                .filter(task -> task != null && !isTerminal(task))
                .count();
        return active < limit;
    }

    private static boolean due(RecoveryRecord record, long now) {
        return record.nextAttemptAt() <= now;
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
        String title = StrUtil.blankToDefault(item.getTitle(), item.getReName());
        if (matchesAny(ani.getExclude(), title, "recovery-subscription-exclude")) {
            return true;
        }
        if (anyDoesNotMatch(ani.getMatch(), title, "recovery-subscription-match")) {
            return true;
        }
        Config config = ConfigUtil.snapshot();
        return Boolean.TRUE.equals(ani.getGlobalExclude()) &&
                matchesAny(config.getExclude(), title, "recovery-global-exclude");
    }

    private static boolean matchesAny(List<String> rules, String value, String scope) {
        return rules != null && rules.stream().anyMatch(rule -> RegexRuleMatcher.matches(rule, value, scope));
    }

    private static boolean anyDoesNotMatch(List<String> rules, String value, String scope) {
        return rules != null && rules.stream().anyMatch(rule -> RegexRuleMatcher.doesNotMatch(rule, value, scope));
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
        if (accepted) {
            if (before.state() != RecoveryState.SUBMITTED) {
                NotificationUtil.send(ConfigUtil.CONFIG, ani, item.getReName(), NotificationStatusEnum.DOWNLOAD_START);
            }
            return;
        }
        String code = StrUtil.blankToDefault(errorCode, "RECOVERY_DOWNLOAD_REJECTED");
        if (before.state() != RecoveryState.RETRY_WAIT || !code.equals(before.lastErrorCode())) {
            NotificationUtil.send(ConfigUtil.CONFIG, ani, item.getReName(), NotificationStatusEnum.ERROR);
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
