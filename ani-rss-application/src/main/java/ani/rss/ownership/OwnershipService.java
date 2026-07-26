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

    public record MediaVerification(boolean manifestAvailable, List<OwnedFile> missingFiles) {
        public MediaVerification {
            missingFiles = List.copyOf(missingFiles == null ? List.of() : missingFiles);
        }

        public boolean healthy() {
            return manifestAvailable && missingFiles.isEmpty();
        }
    }
}
