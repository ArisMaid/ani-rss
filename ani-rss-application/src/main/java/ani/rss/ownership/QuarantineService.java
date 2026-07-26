package ani.rss.ownership;

import ani.rss.commons.PathPolicy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class QuarantineService {
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private final OwnershipService ownershipService;
    private final OwnershipRepository repository;

    public QuarantineService(OwnershipService ownershipService, OwnershipRepository repository) {
        this.ownershipService = ownershipService;
        this.repository = repository;
    }

    public String quarantineOwnership(String ownershipId) {
        DownloadOwnership ownership = repository.find(ownershipId)
                .orElseThrow(() -> new IllegalArgumentException("归属记录不存在"));
        if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
            throw new IllegalStateException("当前归属状态不允许隔离");
        }
        List<OwnedFile> files = ownershipService.listFiles(ownershipId);
        if (files.isEmpty()) {
            throw new IllegalStateException("没有经过验证的文件清单");
        }

        String operationId = UUID.randomUUID().toString();
        Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
        Path trashRoot = root.resolve(".ani-rss-trash");
        Path operationRoot = trashRoot.resolve(operationId);
        List<MovedFile> moved = new ArrayList<>();
        try {
            if (Files.exists(trashRoot) && Files.isSymbolicLink(trashRoot)) {
                throw new IllegalStateException("隔离目录不能是符号链接");
            }
            Files.createDirectories(operationRoot);
            PathPolicy.realPathWithin(root, operationRoot);
            for (OwnedFile ownedFile : files) {
                Path sourceCandidate = PathPolicy.requireSafeDeletionTarget(root,
                        PathPolicy.resolveWithin(root, ownedFile.relativePath()));
                if (!Files.exists(sourceCandidate)) {
                    continue;
                }
                Path source = PathPolicy.realPathWithin(root, sourceCandidate);
                if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
                    throw new IllegalStateException("仅允许隔离清单中的普通文件");
                }
                Path target = PathPolicy.resolveWithin(operationRoot, ownedFile.relativePath());
                if (Files.exists(target)) {
                    throw new IllegalStateException("隔离目标已存在");
                }
                Files.createDirectories(target.getParent());
                PathPolicy.realPathWithin(root, target.getParent());
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                moved.add(new MovedFile(source, target));
            }
            if (moved.isEmpty()) {
                throw new IllegalStateException("清单中的文件均不存在，未执行隔离");
            }
            long now = System.currentTimeMillis();
            long purgeAfter = now + DEFAULT_RETENTION.toMillis();
            List<QuarantineEntry> entries = moved.stream()
                    .map(file -> new QuarantineEntry(
                            UUID.randomUUID().toString(),
                            operationId,
                            ownershipId,
                            file.source().toString(),
                            file.target().toString(),
                            purgeAfter,
                            "QUARANTINED",
                            now
                    )).toList();
            repository.addQuarantineEntries(entries);
            return operationId;
        } catch (Exception e) {
            rollbackMoves(moved);
            throw new IllegalStateException("隔离文件失败，已回滚", e);
        }
    }

    public List<String> quarantineSubscription(String subscriptionId) {
        List<String> operations = new ArrayList<>();
        for (DownloadOwnership ownership : ownershipService.listBySubscription(subscriptionId)) {
            if (ownership.state() == OwnershipState.ACTIVE || ownership.state() == OwnershipState.LEGACY_ADOPTED) {
                operations.add(quarantineOwnership(ownership.ownershipId()));
            }
        }
        return operations;
    }

    public void restore(String operationId) {
        List<QuarantineEntry> entries = repository.listQuarantine(operationId, null);
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("隔离操作不存在");
        }
        List<MovedFile> restored = new ArrayList<>();
        try {
            for (QuarantineEntry entry : entries) {
                if (!"QUARANTINED".equals(entry.state())) {
                    continue;
                }
                DownloadOwnership ownership = repository.find(entry.ownershipId())
                        .orElseThrow(() -> new IllegalStateException("归属记录不存在"));
                Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
                Path expectedOperationRoot = root.resolve(".ani-rss-trash").resolve(operationId).normalize();
                Path source = PathPolicy.realPathWithin(expectedOperationRoot,
                        Path.of(entry.quarantinePath()).toAbsolutePath().normalize());
                Path target = PathPolicy.requireSafeDeletionTarget(root,
                        Path.of(entry.originalPath()).toAbsolutePath().normalize());
                if (Files.exists(target)) {
                    throw new IllegalStateException("原始位置已有同名文件，拒绝覆盖");
                }
                Files.createDirectories(target.getParent());
                PathPolicy.realPathWithin(root, target.getParent());
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                restored.add(new MovedFile(source, target));
            }
            repository.markQuarantineOperationRestored(operationId, entries.get(0).ownershipId());
        } catch (Exception e) {
            rollbackRestores(restored);
            throw new IllegalStateException("恢复隔离文件失败", e);
        }
    }

    public int purgeExpired() {
        List<QuarantineEntry> entries = repository.listQuarantine(null, System.currentTimeMillis());
        int purged = 0;
        for (QuarantineEntry entry : entries) {
            Path path = Path.of(entry.quarantinePath()).toAbsolutePath().normalize();
            try {
                DownloadOwnership ownership = repository.find(entry.ownershipId())
                        .orElseThrow(() -> new IllegalStateException("归属记录不存在"));
                Path expectedOperationRoot = Path.of(ownership.saveRoot()).toAbsolutePath().normalize()
                        .resolve(".ani-rss-trash").resolve(entry.operationId()).normalize();
                PathPolicy.requireWithin(expectedOperationRoot, path);
                if (Files.exists(path)) {
                    path = PathPolicy.realPathWithin(expectedOperationRoot, path);
                    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                        throw new IllegalStateException("隔离项不是普通文件");
                    }
                    Files.delete(path);
                    pruneEmptyParents(path.getParent(), operationRoot(path, entry.operationId()));
                }
                repository.updateQuarantineState(entry.entryId(), "PURGED");
                purged++;
            } catch (IOException e) {
                throw new IllegalStateException("清理隔离文件失败", e);
            }
        }
        return purged;
    }

    public List<QuarantineEntry> list() {
        return repository.listQuarantine(null, null);
    }

    private static Path operationRoot(Path quarantinedPath, String operationId) {
        Path current = quarantinedPath;
        while (current != null && !operationId.equals(current.getFileName().toString())) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("隔离路径不属于对应操作");
        }
        return current;
    }

    private static void pruneEmptyParents(Path current, Path stopAt) throws IOException {
        while (current != null && current.startsWith(stopAt) && !current.equals(stopAt.getParent())) {
            if (!Files.isDirectory(current) || Files.isSymbolicLink(current)) {
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

    private static void rollbackMoves(List<MovedFile> moved) {
        for (int i = moved.size() - 1; i >= 0; i--) {
            MovedFile file = moved.get(i);
            try {
                if (Files.exists(file.target()) && !Files.exists(file.source())) {
                    Files.createDirectories(file.source().getParent());
                    Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (Exception rollbackError) {
                throw new IllegalStateException("隔离回滚失败", rollbackError);
            }
        }
    }

    private static void rollbackRestores(List<MovedFile> restored) {
        for (int i = restored.size() - 1; i >= 0; i--) {
            MovedFile file = restored.get(i);
            try {
                if (Files.exists(file.target()) && !Files.exists(file.source())) {
                    Files.createDirectories(file.source().getParent());
                    Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (Exception rollbackError) {
                throw new IllegalStateException("恢复回滚失败", rollbackError);
            }
        }
    }

    private record MovedFile(Path source, Path target) {
    }
}
