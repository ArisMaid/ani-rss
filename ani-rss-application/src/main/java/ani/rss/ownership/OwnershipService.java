package ani.rss.ownership;

import ani.rss.commons.FileUtils;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class OwnershipService {
    private static final long MOVE_VALIDATION_TTL = Duration.ofMinutes(2).toMillis();

    private final OwnershipRepository repository;
    private final ConcurrentMap<String, Long> validatedMoves = new ConcurrentHashMap<>();

    public OwnershipService(OwnershipRepository repository) {
        this.repository = repository;
    }

    public DownloadOwnership registerPending(Ani ani, Item item, String saveRoot) {
        return registerPending(ConfigUtil.snapshot().getDownloadToolType(), ani, item, saveRoot);
    }

    public DownloadOwnership registerPending(
            String downloaderType, Ani ani, Item item, String saveRoot) {
        String infoHash = item.getInfoHash();
        if (StrUtil.isBlank(infoHash)) {
            throw new IllegalArgumentException("download task has no info-hash");
        }
        if (StrUtil.isBlank(downloaderType)) {
            throw new IllegalArgumentException("downloader type is required");
        }
        long now = System.currentTimeMillis();
        DownloadOwnership ownership = new DownloadOwnership(
                UUID.randomUUID().toString(),
                downloaderType,
                null,
                infoHash,
                ani.getId(),
                ani.getSeason(),
                item.getEpisode() == null ? null : item.getEpisode().toString(),
                FileUtils.getAbsolutePath(saveRoot),
                OwnershipState.PENDING,
                now,
                now);
        return repository.createPending(ownership);
    }

    public void activate(String ownershipId, TorrentsInfo task) {
        activate(ownershipId, null, task);
    }

    public void activate(String ownershipId, String remoteTaskId, TorrentsInfo task) {
        String observedTaskId = remoteTaskId;
        if (task != null) {
            observedTaskId = StrUtil.blankToDefault(task.getId(),
                    StrUtil.blankToDefault(remoteTaskId, task.getHash()));
        }
        repository.activate(ownershipId, observedTaskId);
        if (task != null) {
            captureFiles(ownershipId, task);
        }
    }

    public void markFailed(String ownershipId) {
        markFailed(ownershipId, null);
    }

    public void markFailed(String ownershipId, String remoteTaskId) {
        repository.markFailed(ownershipId, remoteTaskId);
    }

    public Optional<DownloadOwnership> findOwned(TorrentsInfo task) {
        return findOwned(ConfigUtil.snapshot().getDownloadToolType(), task);
    }

    public Optional<DownloadOwnership> findOwned(String downloaderType, TorrentsInfo task) {
        return findManaged(downloaderType, task).filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                ownership.state() == OwnershipState.LEGACY_ADOPTED);
    }

    public Optional<DownloadOwnership> findManaged(TorrentsInfo task) {
        return findManaged(ConfigUtil.snapshot().getDownloadToolType(), task);
    }

    public Optional<DownloadOwnership> findManaged(String downloaderType, TorrentsInfo task) {
        if (task == null) {
            return Optional.empty();
        }
        return repository.findForTask(
                        downloaderType, task.getId(), task.getHash())
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED ||
                        ownership.state() == OwnershipState.PENDING ||
                        ownership.state() == OwnershipState.QUARANTINED);
    }

    public Optional<DownloadOwnership> findManagedByInfoHash(String downloaderType, String infoHash) {
        if (StrUtil.isBlank(downloaderType) || StrUtil.isBlank(infoHash)) {
            return Optional.empty();
        }
        return repository.findByInfoHash(downloaderType, infoHash)
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED ||
                        ownership.state() == OwnershipState.PENDING ||
                        ownership.state() == OwnershipState.QUARANTINED ||
                        ownership.state() == OwnershipState.FAILED);
    }

    /**
     * Resolves an exact owned downloader task. A hash match alone is not
     * enough: a third-party task can reuse the same torrent hash.
     */
    public Optional<DownloadOwnership> findRecoverableOwnedTask(
            String downloaderType, String subscriptionId, String infoHash, TorrentsInfo task) {
        if (task == null || StrUtil.isBlank(subscriptionId)) {
            return Optional.empty();
        }
        return findManaged(downloaderType, task)
                .filter(ownership -> subscriptionId.equals(ownership.subscriptionId()))
                .filter(ownership -> StrUtil.equalsIgnoreCase(ownership.infoHash(), infoHash))
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED);
    }

    public void observeTasks(List<TorrentsInfo> tasks) {
        observeTasks(ConfigUtil.snapshot().getDownloadToolType(), tasks);
    }

    public void observeTasks(String downloaderType, List<TorrentsInfo> tasks) {
        if (tasks == null) {
            return;
        }
        for (TorrentsInfo task : tasks) {
            repository.findForTask(
                            downloaderType, task.getId(), task.getHash())
                    .ifPresent(ownership -> {
                        if (ownership.state() == OwnershipState.PENDING) {
                            repository.activate(ownership.ownershipId(), task.getId());
                        }
                        if (ownership.state() == OwnershipState.ACTIVE ||
                                ownership.state() == OwnershipState.LEGACY_ADOPTED ||
                                ownership.state() == OwnershipState.PENDING) {
                            captureFiles(ownership.ownershipId(), task);
                        }
                    });
        }
    }

    public boolean belongsTo(TorrentsInfo task, String subscriptionId) {
        return belongsTo(ConfigUtil.snapshot().getDownloadToolType(), task, subscriptionId);
    }

    public boolean belongsTo(String downloaderType, TorrentsInfo task, String subscriptionId) {
        return findOwned(downloaderType, task)
                .map(DownloadOwnership::subscriptionId)
                .filter(subscriptionId::equals)
                .isPresent();
    }

    public List<DownloadOwnership> findByEpisode(String subscriptionId, Integer season, String episode) {
        return repository.listBySubscription(subscriptionId).stream()
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED)
                .filter(ownership -> season == null || season.equals(ownership.season()))
                .filter(ownership -> episode == null || episode.equals(ownership.episode()))
                .toList();
    }

    public DownloadOwnership requireOwned(TorrentsInfo task) {
        return requireOwned(ConfigUtil.snapshot().getDownloadToolType(), task);
    }

    public DownloadOwnership requireOwned(String downloaderType, TorrentsInfo task) {
        return findOwned(downloaderType, task).orElseThrow(() ->
                new IllegalStateException("task is not verified as ANI-RSS owned"));
    }

    public DownloadOwnership requireManagedTask(TorrentsInfo task) {
        return requireManagedTask(ConfigUtil.snapshot().getDownloadToolType(), task);
    }

    public DownloadOwnership requireManagedTask(String downloaderType, TorrentsInfo task) {
        return findManaged(downloaderType, task).orElseThrow(() ->
                new IllegalStateException("task is not registered as ANI-RSS managed"));
    }

    public List<DownloadOwnership> listBySubscription(String subscriptionId) {
        return repository.listBySubscription(subscriptionId);
    }

    public List<DownloadOwnership> listAll() {
        return repository.listAll();
    }

    public List<OwnedFile> listFiles(String ownershipId) {
        return repository.listFiles(ownershipId);
    }

    /**
     * Verifies the exact media manifest without following links. An absent
     * manifest is deliberately reported as unknown, never as a missing file.
     */
    public MediaVerification verifyMediaFiles(DownloadOwnership ownership) {
        if (ownership == null) {
            return new MediaVerification(false, List.of());
        }
        List<OwnedFile> manifest = repository.listFiles(ownership.ownershipId());
        if (manifest.isEmpty()) {
            return new MediaVerification(false, List.of());
        }
        List<OwnedFile> missing = new ArrayList<>();
        try {
            Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                return new MediaVerification(true, List.copyOf(manifest));
            }
            for (OwnedFile file : manifest) {
                Path candidate = PathPolicy.resolveWithin(root, file.relativePath());
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(candidate)) {
                    missing.add(file);
                    continue;
                }
                PathPolicy.requireNoSymbolicLinks(root, candidate);
                Path real = PathPolicy.realPathWithin(root, candidate);
                if (file.size() != null && Files.size(real) != file.size()) {
                    missing.add(file);
                }
            }
            return new MediaVerification(true, List.copyOf(missing));
        } catch (Exception e) {
            // An unsafe/unreadable path is not trusted as healthy. Recovery
            // still operates only on this already-verified ownership record.
            return new MediaVerification(true, List.copyOf(manifest));
        }
    }

    /** Returns whether all exact owned media paths have been moved to a target root. */
    public boolean isSubscriptionAtRoot(String subscriptionId, String targetRootValue) {
        if (StrUtil.isBlank(subscriptionId) || StrUtil.isBlank(targetRootValue)) {
            return false;
        }
        try {
            Path targetRoot = Path.of(targetRootValue).toAbsolutePath().normalize();
            List<DownloadOwnership> ownerships = activeOwnerships(subscriptionId);
            if (ownerships.isEmpty()) {
                return false;
            }
            for (DownloadOwnership ownership : ownerships) {
                if (!targetRoot.equals(Path.of(ownership.saveRoot()).toAbsolutePath().normalize())) {
                    return false;
                }
                MediaVerification verification = verifyMediaFiles(ownership);
                if (!verification.manifestAvailable() || !verification.healthy()) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public void captureFiles(String ownershipId, TorrentsInfo task) {
        DownloadOwnership ownership = repository.find(ownershipId)
                .orElseThrow(() -> new IllegalArgumentException("ownership record does not exist"));
        if (task.getFilesSupplier() == null) {
            return;
        }
        List<String> names = task.getFilesSupplier().get();
        if (names == null) {
            return;
        }
        Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
        Map<String, OwnedFile> files = new LinkedHashMap<>();
        for (String name : names) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Path resolved = PathPolicy.resolveWithin(root, name.replace('\\', '/'));
            Path relative = root.relativize(resolved);
            if (relative.getNameCount() == 0) {
                continue;
            }
            String relativePath = relative.toString().replace('\\', '/');
            Long size = Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(resolved) ? safeSize(resolved) : null;
            files.put(relativePath, new OwnedFile(ownershipId, relativePath, "FILE", size));
        }
        repository.replaceFiles(ownershipId, List.copyOf(files.values()));
    }

    public void updateTaskSaveRoot(TorrentsInfo task, String saveRoot) {
        DownloadOwnership ownership = requireOwned(task);
        repository.updateSaveRoot(ownership.ownershipId(), FileUtils.getAbsolutePath(saveRoot));
    }

    /**
     * Preflights a downloader-driven relocation. A short-lived validation is
     * required before the later reconciliation may accept source-missing files.
     */
    public void validateSubscriptionMove(String subscriptionId, String newRootValue) {
        Path newRoot = Path.of(newRootValue).toAbsolutePath().normalize();
        relocationFiles(subscriptionId, newRoot, false);
        validatedMoves.put(moveKey(subscriptionId, newRoot),
                System.currentTimeMillis() + MOVE_VALIDATION_TTL);
    }

    public void moveSubscriptionFiles(String subscriptionId, String newRootValue) {
        Path newRoot = Path.of(newRootValue).toAbsolutePath().normalize();
        String moveKey = moveKey(subscriptionId, newRoot);
        Long validationExpiresAt = validatedMoves.remove(moveKey);
        boolean allowReconcile = validationExpiresAt != null &&
                validationExpiresAt > System.currentTimeMillis();
        List<RelocationFile> files = relocationFiles(subscriptionId, newRoot, allowReconcile);
        if (files.isEmpty()) {
            return;
        }

        List<MovedFile> completed = new ArrayList<>();
        try {
            for (RelocationFile file : files) {
                if (!file.alreadyMoved()) {
                    Path targetParent = requireParent(file.target());
                    Files.createDirectories(targetParent);
                    requireNoLinksFromFileSystemRoot(targetParent);
                    Files.move(file.source(), file.target(), StandardCopyOption.ATOMIC_MOVE);
                    BasicFileAttributes moved = readRegularFile(file.target());
                    if (moved.size() != file.size()) {
                        throw new IllegalStateException("relocated file size changed");
                    }
                }
                completed.add(new MovedFile(file.source(), file.target()));
            }
            repository.updateSaveRoots(rootsFor(files, newRoot));
        } catch (Exception failure) {
            IllegalStateException rollbackFailure = rollbackMoves(completed);
            if (rollbackFailure != null) {
                failure.addSuppressed(rollbackFailure);
            }
            throw new IllegalStateException("move owned files failed; moved files were rolled back", failure);
        }
    }

    public int copySubscriptionFiles(String subscriptionId, String newRootValue) {
        Path newRoot = Path.of(newRootValue).toAbsolutePath().normalize();
        requireSafeTargetRoot(newRoot);
        List<VerifiedOwnedFile> ownedFiles = verifiedSubscriptionFiles(subscriptionId);
        Map<Path, VerifiedOwnedFile> targets = new LinkedHashMap<>();
        Set<Path> sources = new HashSet<>();
        for (VerifiedOwnedFile file : ownedFiles) {
            if (!sources.add(file.path())) {
                continue;
            }
            Path target = PathPolicy.resolveWithin(newRoot, file.ownedFile().relativePath());
            VerifiedOwnedFile collision = targets.putIfAbsent(target, file);
            if (collision != null && !collision.path().equals(file.path())) {
                throw new IllegalStateException("multiple owned files map to the same copy target");
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("copy target already exists; overwrite was refused");
            }
            requireNoLinksFromFileSystemRoot(requireParent(target));
        }

        List<Path> copied = new ArrayList<>();
        try {
            for (Map.Entry<Path, VerifiedOwnedFile> entry : targets.entrySet()) {
                Path target = entry.getKey();
                Path targetParent = requireParent(target);
                Files.createDirectories(targetParent);
                requireNoLinksFromFileSystemRoot(targetParent);
                Files.copy(entry.getValue().path(), target,
                        StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                copied.add(target);
                readRegularFile(target);
            }
            return copied.size();
        } catch (Exception failure) {
            for (int i = copied.size() - 1; i >= 0; i--) {
                Path path = copied.get(i);
                try {
                    Files.deleteIfExists(path);
                    pruneEmptyParents(path.getParent(), newRoot);
                } catch (Exception rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
            }
            throw new IllegalStateException("copy owned files failed; copied files were rolled back", failure);
        }
    }

    public List<VerifiedOwnedFile> verifiedSubscriptionFiles(String subscriptionId) {
        List<VerifiedOwnedFile> result = new ArrayList<>();
        for (DownloadOwnership ownership : activeOwnerships(subscriptionId)) {
            Path root = ownershipRoot(ownership);
            List<OwnedFile> manifest = requireManifest(ownership);
            for (OwnedFile ownedFile : manifest) {
                Path path = safeOwnedPath(root, ownedFile);
                PathPolicy.requireNoSymbolicLinks(root, path);
                try {
                    Path real = PathPolicy.realPathWithin(root, path);
                    BasicFileAttributes attributes = readRegularFile(real);
                    result.add(new VerifiedOwnedFile(ownership, ownedFile, real,
                            attributes.size(), attributes.lastModifiedTime().toMillis()));
                } catch (Exception e) {
                    throw new IllegalStateException("validate owned file failed", e);
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * Prepares exact regular files for an irreversible subscription deletion.
     * Every candidate is checked before any file is removed, and files shared
     * by another active ownership record are rejected.
     */
    public List<VerifiedOwnedFile> prepareSubscriptionFileDeletion(Collection<String> subscriptionIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (subscriptionIds != null) {
            subscriptionIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("at least one subscription id is required");
        }

        Map<Path, VerifiedOwnedFile> files = new LinkedHashMap<>();
        for (String subscriptionId : ids) {
            for (VerifiedOwnedFile file : verifiedSubscriptionFiles(subscriptionId)) {
                VerifiedOwnedFile existing = files.putIfAbsent(file.path(), file);
                if (existing != null && !existing.ownership().ownershipId()
                        .equals(file.ownership().ownershipId())) {
                    throw new IllegalStateException("owned file has conflicting ownership records");
                }
            }
        }
        ensureNoDeletionOwnershipConflicts(files.values());
        return List.copyOf(files.values());
    }

    /**
     * Prepares only the files that can still be proved safe to delete. A stale
     * ownership manifest must not keep a user from deleting its subscription,
     * and it must never authorize deletion of an unverified path.
     */
    public FileDeletionPreparation prepareSubscriptionFileDeletionBestEffort(
            Collection<String> subscriptionIds) {
        LinkedHashSet<String> ids = normalizedSubscriptionIds(subscriptionIds);
        List<VerifiedOwnedFile> candidates = new ArrayList<>();
        int skippedFiles = 0;

        for (String subscriptionId : ids) {
            for (DownloadOwnership ownership : activeOwnerships(subscriptionId)) {
                List<OwnedFile> manifest = repository.listFiles(ownership.ownershipId());
                if (manifest.isEmpty()) {
                    skippedFiles++;
                    continue;
                }

                Path root;
                try {
                    root = ownershipRoot(ownership);
                } catch (Exception ignored) {
                    skippedFiles += manifest.size();
                    continue;
                }

                for (OwnedFile ownedFile : manifest) {
                    try {
                        candidates.add(verifiedOwnedFile(ownership, root, ownedFile));
                    } catch (Exception ignored) {
                        skippedFiles++;
                    }
                }
            }
        }

        Set<Path> conflictedPaths = conflictingOwnershipPaths(candidates);
        Map<Path, VerifiedOwnedFile> selected = new LinkedHashMap<>();
        for (VerifiedOwnedFile candidate : candidates) {
            if (conflictedPaths.contains(candidate.path())) {
                skippedFiles++;
                continue;
            }
            if (selected.putIfAbsent(candidate.path(), candidate) != null) {
                // Repeated manifest entries do not grant a second deletion.
                skippedFiles++;
            }
        }
        return new FileDeletionPreparation(List.copyOf(selected.values()), skippedFiles);
    }

    /** Deletes only file paths produced by {@link #prepareSubscriptionFileDeletion(Collection)}. */
    public int deletePreparedFiles(Collection<VerifiedOwnedFile> files) {
        if (files == null || files.isEmpty()) {
            return 0;
        }
        List<VerifiedOwnedFile> selected = List.copyOf(files);
        ensureNoDeletionOwnershipConflicts(selected);
        for (VerifiedOwnedFile file : selected) {
            validatePreparedFile(file);
        }
        try {
            for (VerifiedOwnedFile file : selected) {
                Files.delete(file.path());
            }
            return selected.size();
        } catch (Exception failure) {
            throw new IllegalStateException("delete verified owned files failed", failure);
        }
    }

    /**
     * Deletes each prevalidated file independently. If a file changes between
     * preparation and deletion, that file is retained and the caller receives
     * an explicit skipped count instead of losing the whole subscription
     * deletion to a stale manifest entry.
     */
    public FileDeletionOutcome deletePreparedFilesBestEffort(Collection<VerifiedOwnedFile> files) {
        FileDeletionResult result = deletePreparedFilesBestEffortWithDetails(files);
        return new FileDeletionOutcome(result.deletedFiles().size(), result.skippedFiles());
    }

    /**
     * Deletes each prevalidated file independently and retains the exact list
     * of files that were actually removed. Callers can use that list to prune
     * only now-empty directories without treating a stale manifest as proof
     * that a directory is disposable.
     */
    public FileDeletionResult deletePreparedFilesBestEffortWithDetails(Collection<VerifiedOwnedFile> files) {
        if (files == null || files.isEmpty()) {
            return new FileDeletionResult(List.of(), 0);
        }

        List<VerifiedOwnedFile> selected = List.copyOf(files);
        Set<Path> conflictedPaths = conflictingOwnershipPaths(selected);
        List<VerifiedOwnedFile> deletedFiles = new ArrayList<>();
        int skippedFiles = 0;
        for (VerifiedOwnedFile file : selected) {
            if (conflictedPaths.contains(file.path())) {
                skippedFiles++;
                continue;
            }
            try {
                validatePreparedFile(file);
                Files.delete(file.path());
                deletedFiles.add(file);
            } catch (Exception ignored) {
                skippedFiles++;
            }
        }
        return new FileDeletionResult(List.copyOf(deletedFiles), skippedFiles);
    }

    /**
     * Removes empty directories left by an explicit subscription deletion.
     * This method never recursively deletes anything. It may only walk above
     * an ownership's saved root when the caller supplies a verified immutable
     * download-root boundary. A directory shared with, or containing, another
     * live ownership root is retained even when it is currently empty.
     */
    public int pruneEmptyDirectoriesAfterDeletion(
            Collection<VerifiedOwnedFile> deletedFiles,
            Collection<DownloadOwnership> releasedOwnerships) {
        return pruneEmptyDirectoriesAfterDeletion(deletedFiles, releasedOwnerships, Map.of());
    }

    /**
     * Removes empty directories left by explicit deletion. A cleanup boundary
     * is exclusive: it is kept, while empty descendants up to it can be
     * removed. Invalid, missing, or out-of-root boundaries are ignored.
     */
    public int pruneEmptyDirectoriesAfterDeletion(
            Collection<VerifiedOwnedFile> deletedFiles,
            Collection<DownloadOwnership> releasedOwnerships,
            Map<String, Path> cleanupBoundaries) {
        if ((deletedFiles == null || deletedFiles.isEmpty()) &&
                (releasedOwnerships == null || releasedOwnerships.isEmpty())) {
            return 0;
        }

        Set<String> releasedIds = new HashSet<>();
        if (releasedOwnerships != null) {
            for (DownloadOwnership ownership : releasedOwnerships) {
                if (ownership != null) {
                    releasedIds.add(ownership.ownershipId());
                }
            }
        }
        List<Path> protectedRoots = liveOwnershipRootsExcept(releasedIds);
        Set<Path> candidates = new HashSet<>();
        if (deletedFiles != null) {
            for (VerifiedOwnedFile file : deletedFiles) {
                if (file == null) {
                    continue;
                }
                try {
                    addCandidatesWithinOwnedRoot(candidates, file.path().getParent(),
                            ownershipRoot(file.ownership()));
                } catch (Exception ignored) {
                    // An invalid path must not authorize directory deletion.
                }
            }
        }
        if (releasedOwnerships != null) {
            for (DownloadOwnership ownership : releasedOwnerships) {
                if (ownership == null) {
                    continue;
                }
                try {
                    Path root = ownershipRoot(ownership);
                    addManifestDirectoryCandidates(candidates, ownership, root);
                    cleanupBoundary(ownership, root, cleanupBoundaries)
                            .ifPresent(boundary -> addCandidatesWithinCleanupScope(
                                    candidates, root, boundary));
                } catch (Exception ignored) {
                    // Unverified roots must not authorize directory deletion.
                }
            }
        }

        List<Path> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(Path::getNameCount).reversed()
                        .thenComparing(Path::toString))
                .toList();
        int deletedDirectories = 0;
        for (Path candidate : ordered) {
            if (isProtectedDirectory(candidate, protectedRoots)) {
                continue;
            }
            if (deleteEmptyDirectory(candidate)) {
                deletedDirectories++;
            }
        }
        return deletedDirectories;
    }

    public void markDeleted(Collection<DownloadOwnership> ownerships) {
        if (ownerships == null) {
            return;
        }
        for (DownloadOwnership ownership : ownerships) {
            if (ownership != null) {
                repository.updateState(ownership.ownershipId(), OwnershipState.DELETED);
            }
        }
    }

    OwnershipRepository repository() {
        return repository;
    }

    private List<RelocationFile> relocationFiles(
            String subscriptionId, Path newRoot, boolean allowReconcile) {
        requireSafeTargetRoot(newRoot);
        List<RelocationFile> result = new ArrayList<>();
        for (DownloadOwnership ownership : activeOwnerships(subscriptionId)) {
            Path root = ownershipRoot(ownership);
            for (OwnedFile ownedFile : requireManifest(ownership)) {
                Path source = safeOwnedPath(root, ownedFile);
                Path target = PathPolicy.resolveWithin(newRoot, ownedFile.relativePath());
                if (source.equals(target)) {
                    continue;
                }
                boolean sourceExists = Files.exists(source, LinkOption.NOFOLLOW_LINKS);
                boolean targetExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
                if (sourceExists == targetExists) {
                    throw new IllegalStateException(sourceExists
                            ? "relocation target already exists; overwrite was refused"
                            : "owned file is absent from both relocation source and target");
                }

                long size;
                boolean alreadyMoved = !sourceExists;
                if (sourceExists) {
                    PathPolicy.requireNoSymbolicLinks(root, source);
                    try {
                        size = readRegularFile(source).size();
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("read relocation source failed", e);
                    }
                } else {
                    if (!allowReconcile || ownedFile.size() == null) {
                        throw new IllegalStateException("existing relocation target was not prevalidated");
                    }
                    requireNoLinksFromFileSystemRoot(target);
                    try {
                        size = readRegularFile(target).size();
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException("read relocation target failed", e);
                    }
                    if (size != ownedFile.size()) {
                        throw new IllegalStateException("existing relocation target size does not match ownership");
                    }
                }
                requireNoLinksFromFileSystemRoot(requireParent(target));
                result.add(new RelocationFile(ownership, ownedFile, source, target, size, alreadyMoved));
            }
        }
        ensureNoPathOwnershipConflicts(result);

        Map<Path, RelocationFile> targets = new LinkedHashMap<>();
        for (RelocationFile file : result) {
            RelocationFile collision = targets.putIfAbsent(file.target(), file);
            if (collision != null && !collision.source().equals(file.source())) {
                throw new IllegalStateException("multiple owned files map to the same relocation target");
            }
        }
        return List.copyOf(targets.values());
    }

    private List<DownloadOwnership> activeOwnerships(String subscriptionId) {
        if (subscriptionId == null || subscriptionId.isBlank()) {
            throw new IllegalArgumentException("subscription id is required");
        }
        return repository.listBySubscription(subscriptionId).stream()
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED)
                .toList();
    }

    private static LinkedHashSet<String> normalizedSubscriptionIds(Collection<String> subscriptionIds) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (subscriptionIds != null) {
            subscriptionIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(id -> !id.isEmpty())
                    .forEach(ids::add);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("at least one subscription id is required");
        }
        return ids;
    }

    private static VerifiedOwnedFile verifiedOwnedFile(
            DownloadOwnership ownership,
            Path root,
            OwnedFile ownedFile) throws java.io.IOException {
        if (ownedFile.size() == null) {
            throw new IllegalStateException("owned file has no verified size");
        }
        Path path = safeOwnedPath(root, ownedFile);
        PathPolicy.requireNoSymbolicLinks(root, path);
        Path real = PathPolicy.realPathWithin(root, path);
        BasicFileAttributes attributes = readRegularFile(real);
        if (attributes.size() != ownedFile.size()) {
            throw new IllegalStateException("owned file size does not match manifest");
        }
        return new VerifiedOwnedFile(ownership, ownedFile, real,
                attributes.size(), attributes.lastModifiedTime().toMillis());
    }

    private Set<Path> conflictingOwnershipPaths(Collection<VerifiedOwnedFile> candidates) {
        Map<Path, Set<String>> owners = new HashMap<>();
        for (DownloadOwnership ownership : repository.listAll()) {
            if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
                continue;
            }
            try {
                Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
                for (OwnedFile file : repository.listFiles(ownership.ownershipId())) {
                    Path path = PathPolicy.resolveWithin(root, file.relativePath());
                    owners.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                            .add(ownership.ownershipId());
                }
            } catch (Exception ignored) {
                // Invalid unrelated ownership cannot authorize deletion.
            }
        }
        for (VerifiedOwnedFile candidate : candidates) {
            owners.computeIfAbsent(candidate.path(), ignored -> new LinkedHashSet<>())
                    .add(candidate.ownership().ownershipId());
        }
        Set<Path> result = new HashSet<>();
        for (Map.Entry<Path, Set<String>> entry : owners.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.add(entry.getKey());
            }
        }
        return result;
    }

    private List<OwnedFile> requireManifest(DownloadOwnership ownership) {
        List<OwnedFile> files = repository.listFiles(ownership.ownershipId());
        if (files.isEmpty()) {
            throw new IllegalStateException("ownership has no verified file manifest");
        }
        return files;
    }

    private static Path ownershipRoot(DownloadOwnership ownership) {
        Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
        if (PathPolicy.isFileSystemRoot(root)) {
            throw new IllegalStateException("ownership root cannot be a filesystem root");
        }
        requireNoLinksFromFileSystemRoot(root);
        return root;
    }

    private List<Path> liveOwnershipRootsExcept(Set<String> releasedIds) {
        List<Path> roots = new ArrayList<>();
        for (DownloadOwnership ownership : repository.listAll()) {
            if (releasedIds.contains(ownership.ownershipId()) || ownership.state() == OwnershipState.DELETED) {
                continue;
            }
            try {
                roots.add(ownershipRoot(ownership));
            } catch (Exception ignored) {
                // An invalid foreign ownership is not a safe deletion boundary.
                // Retain all candidates beneath it by recording its normalized root
                // when possible.
                try {
                    roots.add(Path.of(ownership.saveRoot()).toAbsolutePath().normalize());
                } catch (Exception ignoredAgain) {
                    // No usable root means this record cannot expand deletion scope.
                }
            }
        }
        return List.copyOf(roots);
    }

    private static boolean isProtectedDirectory(Path candidate, Collection<Path> protectedRoots) {
        for (Path protectedRoot : protectedRoots) {
            if (candidate.equals(protectedRoot) || protectedRoot.startsWith(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean deleteEmptyDirectory(Path directory) {
        try {
            if (PathPolicy.isFileSystemRoot(directory) || Files.isSymbolicLink(directory) ||
                    !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            requireNoLinksFromFileSystemRoot(directory);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                if (stream.iterator().hasNext()) {
                    return false;
                }
            }
            Files.delete(directory);
            return true;
        } catch (java.io.IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void addCandidatesWithinOwnedRoot(
            Set<Path> candidates, Path start, Path root) {
        for (Path current = start; current != null; current = current.getParent()) {
            PathPolicy.requireWithin(root, current);
            candidates.add(current);
            if (current.equals(root)) {
                return;
            }
        }
    }

    /**
     * Adds only the parent directories named by the ownership manifest. This
     * covers an already-missing file without scanning or recursively removing
     * arbitrary descendants of the save root. Each resulting directory still
     * has to be empty at deletion time before it can be removed.
     */
    private void addManifestDirectoryCandidates(
            Set<Path> candidates, DownloadOwnership ownership, Path root) {
        for (OwnedFile ownedFile : repository.listFiles(ownership.ownershipId())) {
            try {
                Path parent = safeOwnedPath(root, ownedFile).getParent();
                if (parent != null) {
                    addCandidatesWithinOwnedRoot(candidates, parent, root);
                }
            } catch (Exception ignored) {
                // A malformed manifest must never expand directory cleanup.
            }
        }
    }

    private static Optional<Path> cleanupBoundary(
            DownloadOwnership ownership, Path root, Map<String, Path> cleanupBoundaries) {
        if (cleanupBoundaries == null || ownership == null) {
            return Optional.empty();
        }
        Path candidate = cleanupBoundaries.get(ownership.ownershipId());
        if (candidate == null) {
            return Optional.empty();
        }
        Path boundary = candidate.toAbsolutePath().normalize();
        if (PathPolicy.isFileSystemRoot(boundary) || root.equals(boundary) || !root.startsWith(boundary)) {
            return Optional.empty();
        }
        return Optional.of(boundary);
    }

    private static void addCandidatesWithinCleanupScope(
            Set<Path> candidates, Path root, Path boundary) {
        PathPolicy.requireWithin(boundary, root);
        for (Path current = root; current != null && !current.equals(boundary); current = current.getParent()) {
            candidates.add(current);
        }
    }

    private static Path safeOwnedPath(Path root, OwnedFile ownedFile) {
        return PathPolicy.requireSafeDeletionTarget(root,
                PathPolicy.resolveWithin(root, ownedFile.relativePath()));
    }

    private void ensureNoPathOwnershipConflicts(Collection<RelocationFile> selected) {
        Map<Path, Set<String>> owners = new HashMap<>();
        for (DownloadOwnership ownership : repository.listAll()) {
            if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
                continue;
            }
            Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            for (OwnedFile file : repository.listFiles(ownership.ownershipId())) {
                try {
                    Path path = PathPolicy.resolveWithin(root, file.relativePath());
                    owners.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                            .add(ownership.ownershipId());
                } catch (RuntimeException ignored) {
                    // Invalid unrelated ownership cannot authorize a destructive move.
                }
            }
        }
        for (RelocationFile file : selected) {
            if (owners.getOrDefault(file.source(), Set.of()).size() > 1) {
                throw new IllegalStateException("owned source has conflicting ownership records");
            }
        }
    }

    private void ensureNoDeletionOwnershipConflicts(Collection<VerifiedOwnedFile> selected) {
        Map<Path, Set<String>> owners = new HashMap<>();
        for (DownloadOwnership ownership : repository.listAll()) {
            if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
                continue;
            }
            Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
            for (OwnedFile file : repository.listFiles(ownership.ownershipId())) {
                try {
                    Path path = PathPolicy.resolveWithin(root, file.relativePath());
                    owners.computeIfAbsent(path, ignored -> new LinkedHashSet<>())
                            .add(ownership.ownershipId());
                } catch (RuntimeException ignored) {
                    // Invalid unrelated ownership cannot authorize deletion.
                }
            }
        }
        for (VerifiedOwnedFile file : selected) {
            if (owners.getOrDefault(file.path(), Set.of()).size() > 1) {
                throw new IllegalStateException("owned file has conflicting ownership records");
            }
        }
    }

    private static void validatePreparedFile(VerifiedOwnedFile file) {
        if (file == null) {
            throw new IllegalArgumentException("owned file is required");
        }
        Path root = ownershipRoot(file.ownership());
        Path expected = safeOwnedPath(root, file.ownedFile());
        if (!expected.equals(file.path())) {
            throw new IllegalArgumentException("owned file path changed after validation");
        }
        PathPolicy.requireNoSymbolicLinks(root, expected);
        try {
            BasicFileAttributes attributes = readRegularFile(expected);
            if (attributes.size() != file.size()) {
                throw new IllegalStateException("owned file size changed after validation");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("validate owned file before deletion failed", e);
        }
    }

    private static Map<String, String> rootsFor(List<RelocationFile> files, Path newRoot) {
        Map<String, String> roots = new LinkedHashMap<>();
        for (RelocationFile file : files) {
            roots.put(file.ownership().ownershipId(), newRoot.toString());
        }
        return roots;
    }

    private static void requireSafeTargetRoot(Path root) {
        if (PathPolicy.isFileSystemRoot(root)) {
            throw new IllegalArgumentException("filesystem root cannot be used as a relocation target");
        }
        requireNoLinksFromFileSystemRoot(root);
    }

    private static void requireNoLinksFromFileSystemRoot(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path fileSystemRoot = absolute.getRoot();
        if (fileSystemRoot == null) {
            throw new IllegalArgumentException("path has no filesystem root");
        }
        PathPolicy.requireNoSymbolicLinks(fileSystemRoot, absolute);
    }

    private static BasicFileAttributes readRegularFile(Path path) throws java.io.IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("owned path is not a regular non-symbolic file");
        }
        return attributes;
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
                    throw new IllegalStateException("relocation rollback source already exists");
                }
                Files.createDirectories(requireParent(file.source()));
                Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception rollbackError) {
                if (failure == null) {
                    failure = new IllegalStateException("relocation rollback failed");
                }
                failure.addSuppressed(rollbackError);
            }
        }
        return failure;
    }

    private static void pruneEmptyParents(Path current, Path stopAt) throws java.io.IOException {
        while (current != null && current.startsWith(stopAt) && !current.equals(stopAt)) {
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                return;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(current)) {
                if (stream.iterator().hasNext()) {
                    return;
                }
            }
            Files.delete(current);
            current = current.getParent();
        }
    }

    private static Path requireParent(Path path) {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalStateException("owned file path must have a parent directory");
        }
        return parent;
    }

    private static String moveKey(String subscriptionId, Path newRoot) {
        return subscriptionId + "\u0000" + newRoot;
    }

    private static Long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception ignored) {
            return null;
        }
    }

    private record MovedFile(Path source, Path target) {
    }

    private record RelocationFile(
            DownloadOwnership ownership,
            OwnedFile ownedFile,
            Path source,
            Path target,
            long size,
            boolean alreadyMoved) {
    }

    public record VerifiedOwnedFile(
            DownloadOwnership ownership,
            OwnedFile ownedFile,
            Path path,
            long size,
            long lastModified) {
    }

    public record FileDeletionPreparation(List<VerifiedOwnedFile> files, int skippedFiles) {
        public FileDeletionPreparation {
            files = List.copyOf(files == null ? List.of() : files);
        }
    }

    public record FileDeletionOutcome(int deletedFiles, int skippedFiles) {
    }

    public record FileDeletionResult(List<VerifiedOwnedFile> deletedFiles, int skippedFiles) {
        public FileDeletionResult {
            deletedFiles = List.copyOf(deletedFiles == null ? List.of() : deletedFiles);
        }
    }

    public record MediaVerification(boolean manifestAvailable, List<OwnedFile> missingFiles) {
        public MediaVerification {
            missingFiles = List.copyOf(missingFiles == null ? List.of() : missingFiles);
        }

        public boolean healthy() {
            return manifestAvailable && missingFiles.isEmpty();
        }
    }
}
