package ani.rss.ownership;

import ani.rss.commons.PathPolicy;
import ani.rss.recovery.MissingEpisodeRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
public class QuarantineService {
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);
    public static final Duration PLAN_TTL = Duration.ofMinutes(10);

    private final OwnershipService ownershipService;
    private final OwnershipRepository repository;
    private final MissingEpisodeRecoveryService recoveryService;
    private final ConcurrentMap<String, PlanState> plans = new ConcurrentHashMap<>();

    public QuarantineService(OwnershipService ownershipService, OwnershipRepository repository) {
        this.ownershipService = ownershipService;
        this.repository = repository;
        this.recoveryService = null;
    }

    @Autowired
    public QuarantineService(
            OwnershipService ownershipService,
            OwnershipRepository repository,
            ObjectProvider<MissingEpisodeRecoveryService> recoveryService) {
        this.ownershipService = ownershipService;
        this.repository = repository;
        this.recoveryService = recoveryService.getIfAvailable();
    }

    public DestructiveOperationPlan planOwnership(String ownershipId) {
        return planOwnerships(List.of(ownershipId));
    }

    public DestructiveOperationPlan planOwnerships(Collection<String> ownershipIds) {
        trimPlans();
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        if (ownershipIds != null) {
            ownershipIds.stream()
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .forEach(uniqueIds::add);
        }
        if (uniqueIds.isEmpty()) {
            throw new IllegalArgumentException("at least one ownership id is required");
        }

        String operationId = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        long expiresAt = createdAt + PLAN_TTL.toMillis();
        List<PlanFileState> files = new ArrayList<>();
        Map<String, DownloadOwnership> ownerships = new HashMap<>();
        Map<String, Set<OwnedFile>> manifests = new HashMap<>();

        for (String ownershipId : uniqueIds) {
            DownloadOwnership ownership = repository.find(ownershipId)
                    .orElseThrow(() -> new IllegalArgumentException("ownership record does not exist"));
            requireQuarantinable(ownership);
            List<OwnedFile> ownedFiles = ownershipService.listFiles(ownershipId);
            if (ownedFiles.isEmpty()) {
                throw new IllegalStateException("ownership has no verified file manifest");
            }
            ownerships.put(ownershipId, ownership);
            manifests.put(ownershipId, Set.copyOf(ownedFiles));
            for (OwnedFile ownedFile : ownedFiles) {
                files.add(planFile(operationId, ownership, ownedFile));
            }
        }

        files.sort(Comparator.comparing(file -> file.source().toString()));
        ensureNoOwnershipConflicts(files);
        List<PlannedFile> viewFiles = files.stream()
                .map(file -> new PlannedFile(
                        file.ownership().ownershipId(),
                        file.ownedFile().relativePath(),
                        file.source().toString(),
                        file.size(),
                        file.lastModified()))
                .toList();
        DestructiveOperationPlan view = new DestructiveOperationPlan(
                operationId, createdAt, expiresAt, List.copyOf(uniqueIds), viewFiles);
        PlanState state = new PlanState(view, Map.copyOf(ownerships), Map.copyOf(manifests), List.copyOf(files));
        if (plans.putIfAbsent(operationId, state) != null) {
            throw new IllegalStateException("operation id collision");
        }
        return view;
    }

    public String executePlan(String operationId) {
        PlanState plan = plans.get(operationId);
        if (plan == null) {
            throw new IllegalArgumentException("destructive operation plan does not exist");
        }
        synchronized (plan) {
            if (plans.get(operationId) != plan) {
                throw new IllegalArgumentException("destructive operation plan has already been consumed");
            }
            if (plan.view().expiresAt() <= System.currentTimeMillis()) {
                plans.remove(operationId, plan);
                throw new IllegalStateException("destructive operation plan has expired");
            }
            revalidate(plan);

            List<MovedFile> moved = new ArrayList<>();
            try {
                for (PlanFileState file : plan.files()) {
                    Path targetParent = requireParent(file.target());
                    Files.createDirectories(targetParent);
                    PathPolicy.requireNoSymbolicLinks(file.root(), targetParent);
                    PathPolicy.realPathWithin(file.root(), targetParent);
                    Files.move(file.source(), file.target(), StandardCopyOption.ATOMIC_MOVE);
                    moved.add(new MovedFile(file.source(), file.target(), file.root()));
                }
                long now = System.currentTimeMillis();
                long purgeAfter = now + DEFAULT_RETENTION.toMillis();
                List<QuarantineEntry> entries = plan.files().stream()
                        .map(file -> new QuarantineEntry(
                                UUID.randomUUID().toString(),
                                operationId,
                                file.ownership().ownershipId(),
                                file.source().toString(),
                                file.target().toString(),
                                purgeAfter,
                                "QUARANTINED",
                                now,
                                file.ownership().state().name()))
                        .toList();
                repository.addQuarantineEntries(entries);
                if (recoveryService != null) {
                    for (DownloadOwnership ownership : plan.ownerships().values()) {
                        recoveryService.cancel(ownership.subscriptionId(), ownership.infoHash());
                    }
                }
                plans.remove(operationId, plan);
                return operationId;
            } catch (Exception failure) {
                IllegalStateException rollbackFailure = rollbackMoves(moved);
                if (rollbackFailure != null) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw new IllegalStateException("quarantine failed; moved files were rolled back", failure);
            }
        }
    }

    public void cancelPlan(String operationId) {
        if (plans.remove(operationId) == null) {
            throw new IllegalArgumentException("destructive operation plan does not exist");
        }
    }

    public String quarantineOwnership(String ownershipId) {
        DestructiveOperationPlan plan = planOwnership(ownershipId);
        return executePlan(plan.operationId());
    }

    public String quarantineSubscription(String subscriptionId) {
        List<String> ownershipIds = ownershipService.listBySubscription(subscriptionId).stream()
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED)
                .map(DownloadOwnership::ownershipId)
                .toList();
        DestructiveOperationPlan plan = planOwnerships(ownershipIds);
        return executePlan(plan.operationId());
    }

    public void restore(String operationId) {
        List<QuarantineEntry> entries = repository.listQuarantine(operationId, null);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("quarantine operation does not exist");
        }
        if (entries.stream().anyMatch(entry -> !"QUARANTINED".equals(entry.state()))) {
            throw new IllegalStateException("quarantine operation is not fully restorable");
        }

        List<RestoreFile> files = new ArrayList<>();
        Set<Path> targets = new HashSet<>();
        for (QuarantineEntry entry : entries) {
            try {
                DownloadOwnership ownership = repository.find(entry.ownershipId())
                        .orElseThrow(() -> new IllegalStateException("ownership record does not exist"));
                if (ownership.state() != OwnershipState.QUARANTINED) {
                    throw new IllegalStateException("ownership is not quarantined");
                }
                Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
                Path operationRoot = root.resolve(".ani-rss-trash").resolve(operationId).normalize();
                Path sourceCandidate = PathPolicy.requireWithin(operationRoot,
                        Path.of(entry.quarantinePath()).toAbsolutePath().normalize());
                PathPolicy.requireNoSymbolicLinks(operationRoot, sourceCandidate);
                Path source = PathPolicy.realPathWithin(operationRoot, sourceCandidate);
                BasicFileAttributes attributes = readRegularFile(source);
                Path target = PathPolicy.requireSafeDeletionTarget(root,
                        Path.of(entry.originalPath()).toAbsolutePath().normalize());
                PathPolicy.requireNoSymbolicLinks(root, requireParent(target));
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || !targets.add(target)) {
                    throw new IllegalStateException("restore target already exists or is duplicated");
                }
                files.add(new RestoreFile(source, target, root, operationRoot, attributes.size()));
            } catch (IOException e) {
                throw new IllegalStateException("validate quarantine restore failed", e);
            }
        }

        List<MovedFile> restored = new ArrayList<>();
        try {
            for (RestoreFile file : files) {
                Path targetParent = requireParent(file.target());
                Files.createDirectories(targetParent);
                PathPolicy.requireNoSymbolicLinks(file.root(), targetParent);
                PathPolicy.realPathWithin(file.root(), targetParent);
                Files.move(file.source(), file.target(), StandardCopyOption.ATOMIC_MOVE);
                restored.add(new MovedFile(file.source(), file.target(), file.root()));
            }
            repository.markQuarantineOperationRestored(operationId);
            for (RestoreFile file : files) {
                pruneEmptyParents(file.source().getParent(), file.operationRoot());
            }
        } catch (Exception failure) {
            IllegalStateException rollbackFailure = rollbackRestores(restored);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("restore quarantine operation failed", failure);
        }
    }

    public int purgeExpired() {
        LinkedHashSet<String> operations = repository.listQuarantine(null, System.currentTimeMillis()).stream()
                .map(QuarantineEntry::operationId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        int purged = 0;
        for (String operationId : operations) {
            try {
                purged += purgeOperation(operationId, false);
            } catch (RuntimeException e) {
                log.warn("failed to purge expired quarantine operation {} type:{}",
                        operationId, e.getClass().getSimpleName());
            }
        }
        return purged;
    }

    public int purge(String operationId, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("immediate purge requires explicit confirmation");
        }
        return purgeOperation(operationId, true);
    }

    public List<QuarantineEntry> list() {
        return repository.listQuarantine(null, null);
    }

    private int purgeOperation(String operationId, boolean immediate) {
        List<QuarantineEntry> entries = repository.listQuarantine(operationId, null).stream()
                .filter(entry -> "QUARANTINED".equals(entry.state()))
                .toList();
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("quarantine operation has no purgeable files");
        }
        long now = System.currentTimeMillis();
        if (!immediate && entries.stream().anyMatch(entry -> entry.purgeAfter() > now)) {
            throw new IllegalStateException("quarantine retention period has not elapsed");
        }

        Map<String, PurgeFile> files = new HashMap<>();
        for (QuarantineEntry entry : entries) {
            try {
                DownloadOwnership ownership = repository.find(entry.ownershipId())
                        .orElseThrow(() -> new IllegalStateException("ownership record does not exist"));
                Path operationRoot = Path.of(ownership.saveRoot()).toAbsolutePath().normalize()
                        .resolve(".ani-rss-trash").resolve(operationId).normalize();
                Path candidate = PathPolicy.requireWithin(operationRoot,
                        Path.of(entry.quarantinePath()).toAbsolutePath().normalize());
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    PathPolicy.requireNoSymbolicLinks(operationRoot, candidate);
                    Path real = PathPolicy.realPathWithin(operationRoot, candidate);
                    readRegularFile(real);
                    files.put(entry.entryId(), new PurgeFile(entry, real, operationRoot));
                } else {
                    files.put(entry.entryId(), new PurgeFile(entry, null, operationRoot));
                }
            } catch (IOException e) {
                throw new IllegalStateException("validate quarantine purge failed", e);
            }
        }

        int purged = 0;
        for (PurgeFile file : files.values()) {
            try {
                if (file.path() != null) {
                    Files.delete(file.path());
                    pruneEmptyParents(file.path().getParent(), file.operationRoot());
                }
                repository.updateQuarantineState(file.entry().entryId(), "PURGED");
                purged++;
            } catch (IOException e) {
                throw new IllegalStateException("purge quarantine file failed", e);
            }
        }
        return purged;
    }

    private PlanFileState planFile(String operationId, DownloadOwnership ownership, OwnedFile ownedFile) {
        try {
            Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                throw new IllegalStateException("ownership root is unavailable or symbolic");
            }
            Path sourceCandidate = PathPolicy.requireSafeDeletionTarget(root,
                    PathPolicy.resolveWithin(root, ownedFile.relativePath()));
            PathPolicy.requireNoSymbolicLinks(root, sourceCandidate);
            if (!Files.exists(sourceCandidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("owned file is missing; destructive operation was refused");
            }
            Path source = PathPolicy.realPathWithin(root, sourceCandidate);
            BasicFileAttributes attributes = readRegularFile(source);
            Path target = root.resolve(".ani-rss-trash")
                    .resolve(operationId)
                    .resolve(ownership.ownershipId())
                    .resolve(ownedFile.relativePath())
                    .normalize();
            PathPolicy.requireWithin(root, target);
            PathPolicy.requireNoSymbolicLinks(root, target.getParent());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("quarantine target already exists");
            }
            return new PlanFileState(ownership, ownedFile, root, source, target,
                    attributes.size(), attributes.lastModifiedTime().toMillis(), fileKey(attributes));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("owned file manifest is unsafe", e);
        } catch (IOException e) {
            throw new IllegalStateException("validate owned file failed", e);
        }
    }

    private void revalidate(PlanState plan) {
        for (Map.Entry<String, DownloadOwnership> entry : plan.ownerships().entrySet()) {
            DownloadOwnership current = repository.find(entry.getKey())
                    .orElseThrow(() -> new IllegalStateException("ownership record no longer exists"));
            DownloadOwnership planned = entry.getValue();
            if (current.state() != planned.state() || !Objects.equals(current.saveRoot(), planned.saveRoot())) {
                throw new IllegalStateException("ownership changed after plan creation");
            }
            Set<OwnedFile> manifest = Set.copyOf(repository.listFiles(entry.getKey()));
            if (!manifest.equals(plan.manifests().get(entry.getKey()))) {
                throw new IllegalStateException("owned file manifest changed after plan creation");
            }
        }
        for (PlanFileState file : plan.files()) {
            try {
                PathPolicy.requireNoSymbolicLinks(file.root(), file.source());
                BasicFileAttributes current = readRegularFile(file.source());
                if (current.size() != file.size() ||
                        current.lastModifiedTime().toMillis() != file.lastModified() ||
                        !Objects.equals(fileKey(current), file.fileKey()) ||
                        Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("owned file changed after plan creation");
                }
            } catch (IOException e) {
                throw new IllegalStateException("revalidate owned file failed", e);
            }
        }
        ensureNoOwnershipConflicts(plan.files());
    }

    private void ensureNoOwnershipConflicts(List<PlanFileState> selectedFiles) {
        Map<Path, Set<String>> ownersByPath = new HashMap<>();
        Map<String, Set<String>> ownersByFileKey = new HashMap<>();
        for (DownloadOwnership ownership : repository.listAll()) {
            if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
                continue;
            }
            Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            for (OwnedFile ownedFile : repository.listFiles(ownership.ownershipId())) {
                try {
                    Path candidate = PathPolicy.resolveWithin(root, ownedFile.relativePath());
                    ownersByPath.computeIfAbsent(candidate, ignored -> new HashSet<>())
                            .add(ownership.ownershipId());
                    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                            Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(candidate)) {
                        BasicFileAttributes attributes = Files.readAttributes(
                                candidate, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        String key = fileKey(attributes);
                        if (key != null) {
                            ownersByFileKey.computeIfAbsent(key, ignored -> new HashSet<>())
                                    .add(ownership.ownershipId());
                        }
                    }
                } catch (RuntimeException | IOException ignored) {
                    // Invalid unrelated manifests are not trusted as evidence for deletion.
                }
            }
        }

        for (PlanFileState selected : selectedFiles) {
            Set<String> pathOwners = ownersByPath.getOrDefault(selected.source(), Set.of());
            if (pathOwners.size() > 1) {
                throw new IllegalStateException("owned file has conflicting ownership records");
            }
            if (selected.fileKey() != null &&
                    ownersByFileKey.getOrDefault(selected.fileKey(), Set.of()).size() > 1) {
                throw new IllegalStateException("owned file is shared by conflicting ownership records");
            }
        }
    }

    private static void requireQuarantinable(DownloadOwnership ownership) {
        if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
            throw new IllegalStateException("ownership state does not allow quarantine");
        }
    }

    private static BasicFileAttributes readRegularFile(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("only regular non-symbolic files may be quarantined");
        }
        return attributes;
    }

    private static String fileKey(BasicFileAttributes attributes) {
        return attributes.fileKey() == null ? null : attributes.fileKey().toString();
    }

    private void trimPlans() {
        long now = System.currentTimeMillis();
        plans.entrySet().removeIf(entry -> entry.getValue().view().expiresAt() <= now);
        if (plans.size() > 10_000) {
            plans.entrySet().stream()
                    .sorted(Map.Entry.comparingByValue(
                            Comparator.comparingLong(value -> value.view().expiresAt())))
                    .limit(plans.size() - 10_000L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(plans::remove);
        }
    }

    private static void pruneEmptyParents(Path current, Path stopAt) throws IOException {
        while (current != null && current.startsWith(stopAt)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                if (stream.iterator().hasNext()) {
                    return;
                }
            }
            Files.delete(current);
            if (current.equals(stopAt)) {
                return;
            }
            current = current.getParent();
        }
    }

    private static IllegalStateException rollbackMoves(List<MovedFile> moved) {
        IllegalStateException failure = null;
        for (int i = moved.size() - 1; i >= 0; i--) {
            MovedFile file = moved.get(i);
            try {
                if (!Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.exists(file.source(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("rollback source already exists");
                }
                Files.createDirectories(requireParent(file.source()));
                Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
                pruneEmptyParents(file.target().getParent(), operationRoot(file.target()));
            } catch (Exception rollbackError) {
                if (failure == null) {
                    failure = new IllegalStateException("quarantine rollback failed");
                }
                failure.addSuppressed(rollbackError);
            }
        }
        return failure;
    }

    private static IllegalStateException rollbackRestores(List<MovedFile> restored) {
        IllegalStateException failure = null;
        for (int i = restored.size() - 1; i >= 0; i--) {
            MovedFile file = restored.get(i);
            try {
                if (!Files.exists(file.target(), LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (Files.exists(file.source(), LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("restore rollback target already exists");
                }
                Files.createDirectories(requireParent(file.source()));
                Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception rollbackError) {
                if (failure == null) {
                    failure = new IllegalStateException("restore rollback failed");
                }
                failure.addSuppressed(rollbackError);
            }
        }
        return failure;
    }

    private static Path operationRoot(Path quarantinedPath) {
        Path current = quarantinedPath;
        while (current != null) {
            Path parent = current.getParent();
            if (parent == null) {
                break;
            }
            Path parentName = parent.getFileName();
            if (parentName != null && ".ani-rss-trash".equals(parentName.toString())) {
                return current;
            }
            current = parent;
        }
        throw new IllegalStateException("quarantine path has no operation root");
    }

    private static Path requireParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("quarantine file path must have a parent directory");
        }
        return parent;
    }

    public record DestructiveOperationPlan(
            String operationId,
            long createdAt,
            long expiresAt,
            List<String> ownershipIds,
            List<PlannedFile> files) {
        public DestructiveOperationPlan {
            ownershipIds = ownershipIds == null ? List.of() : List.copyOf(ownershipIds);
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record PlannedFile(
            String ownershipId,
            String relativePath,
            String path,
            long size,
            long lastModified) {
    }

    private record PlanState(
            DestructiveOperationPlan view,
            Map<String, DownloadOwnership> ownerships,
            Map<String, Set<OwnedFile>> manifests,
            List<PlanFileState> files) {
    }

    private record PlanFileState(
            DownloadOwnership ownership,
            OwnedFile ownedFile,
            Path root,
            Path source,
            Path target,
            long size,
            long lastModified,
            String fileKey) {
    }

    private record RestoreFile(Path source, Path target, Path root, Path operationRoot, long size) {
    }

    private record PurgeFile(QuarantineEntry entry, Path path, Path operationRoot) {
    }

    private record MovedFile(Path source, Path target, Path root) {
    }
}
