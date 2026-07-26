package ani.rss.ownership;

import ani.rss.commons.FileUtils;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.Ani;
import ani.rss.entity.Item;
import ani.rss.entity.torrent.TorrentsInfo;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class OwnershipService {
    private final OwnershipRepository repository;

    public OwnershipService(OwnershipRepository repository) {
        this.repository = repository;
    }

    public DownloadOwnership registerPending(Ani ani, Item item, String saveRoot) {
        String infoHash = item.getInfoHash();
        if (StrUtil.isBlank(infoHash)) {
            throw new IllegalArgumentException("缺少下载任务 info-hash，无法登记归属");
        }
        long now = System.currentTimeMillis();
        DownloadOwnership ownership = new DownloadOwnership(
                UUID.randomUUID().toString(),
                ConfigUtil.CONFIG.getDownloadToolType(),
                null,
                infoHash,
                ani.getId(),
                ani.getSeason(),
                item.getEpisode() == null ? null : item.getEpisode().toString(),
                FileUtils.getAbsolutePath(saveRoot),
                OwnershipState.PENDING,
                now,
                now
        );
        return repository.createPending(ownership);
    }

    public void activate(String ownershipId, TorrentsInfo task) {
        repository.activate(ownershipId, task == null ? null : task.getId());
        if (task != null) {
            captureFiles(ownershipId, task);
        }
    }

    public void markFailed(String ownershipId) {
        repository.markFailed(ownershipId);
    }

    public Optional<DownloadOwnership> findOwned(TorrentsInfo task) {
        return findManaged(task).filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                ownership.state() == OwnershipState.LEGACY_ADOPTED);
    }

    public Optional<DownloadOwnership> findManaged(TorrentsInfo task) {
        if (task == null) {
            return Optional.empty();
        }
        return repository.findForTask(
                        ConfigUtil.CONFIG.getDownloadToolType(),
                        task.getId(),
                        task.getHash())
                .filter(ownership -> ownership.state() == OwnershipState.ACTIVE ||
                        ownership.state() == OwnershipState.LEGACY_ADOPTED ||
                        ownership.state() == OwnershipState.PENDING ||
                        ownership.state() == OwnershipState.QUARANTINED);
    }

    public void observeTasks(List<TorrentsInfo> tasks) {
        for (TorrentsInfo task : tasks) {
            repository.findForTask(
                            ConfigUtil.CONFIG.getDownloadToolType(),
                            task.getId(),
                            task.getHash())
                    .ifPresent(ownership -> {
                        if (ownership.state() == OwnershipState.PENDING) {
                            repository.activate(ownership.ownershipId(), task.getId());
                        }
                        if (ownership.state() != OwnershipState.FAILED) {
                            captureFiles(ownership.ownershipId(), task);
                        }
                    });
        }
    }

    public boolean belongsTo(TorrentsInfo task, String subscriptionId) {
        return findOwned(task)
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
        return findOwned(task).orElseThrow(() ->
                new IllegalStateException("任务未通过 ANI-RSS 归属验证，已拒绝破坏性操作"));
    }

    public DownloadOwnership requireManagedTask(TorrentsInfo task) {
        return findManaged(task).orElseThrow(() ->
                new IllegalStateException("任务未通过 ANI-RSS 归属验证，已拒绝远端操作"));
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

    public void captureFiles(String ownershipId, TorrentsInfo task) {
        DownloadOwnership ownership = repository.find(ownershipId)
                .orElseThrow(() -> new IllegalArgumentException("归属记录不存在"));
        if (task.getFilesSupplier() == null) {
            return;
        }
        Path root = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
        List<OwnedFile> files = new ArrayList<>();
        for (String name : task.getFilesSupplier().get()) {
            if (StrUtil.isBlank(name)) {
                continue;
            }
            Path resolved = PathPolicy.resolveWithin(root, name.replace('\\', '/'));
            Path relative = root.relativize(resolved);
            if (relative.getNameCount() == 0) {
                continue;
            }
            Long size = Files.isRegularFile(resolved) ? safeSize(resolved) : null;
            files.add(new OwnedFile(ownershipId, relative.toString().replace('\\', '/'), "FILE", size));
        }
        repository.replaceFiles(ownershipId, files);
    }

    public void updateTaskSaveRoot(TorrentsInfo task, String saveRoot) {
        DownloadOwnership ownership = requireOwned(task);
        repository.updateSaveRoot(ownership.ownershipId(), FileUtils.getAbsolutePath(saveRoot));
    }

    public void moveSubscriptionFiles(String subscriptionId, String newRootValue) {
        Path newRoot = Path.of(newRootValue).toAbsolutePath().normalize();
        List<MovedFile> moved = new ArrayList<>();
        Map<String, String> newRoots = new LinkedHashMap<>();
        try {
            for (DownloadOwnership ownership : repository.listBySubscription(subscriptionId)) {
                if (ownership.state() != OwnershipState.ACTIVE && ownership.state() != OwnershipState.LEGACY_ADOPTED) {
                    continue;
                }
                Path oldRoot = Path.of(ownership.saveRoot()).toAbsolutePath().normalize();
                for (OwnedFile ownedFile : repository.listFiles(ownership.ownershipId())) {
                    Path source = PathPolicy.resolveWithin(oldRoot, ownedFile.relativePath());
                    Path target = PathPolicy.resolveWithin(newRoot, ownedFile.relativePath());
                    if (!Files.exists(source)) {
                        if (!Files.exists(target)) {
                            throw new IllegalStateException("归属文件在原位置和目标位置均不存在");
                        }
                        continue;
                    }
                    if (Files.exists(target)) {
                        throw new IllegalStateException("目标文件已存在，拒绝覆盖");
                    }
                    Files.createDirectories(target.getParent());
                    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                    moved.add(new MovedFile(source, target));
                }
                newRoots.put(ownership.ownershipId(), newRoot.toString());
            }
            repository.updateSaveRoots(newRoots);
        } catch (Exception e) {
            for (int i = moved.size() - 1; i >= 0; i--) {
                MovedFile file = moved.get(i);
                try {
                    if (Files.exists(file.target()) && !Files.exists(file.source())) {
                        Files.createDirectories(file.source().getParent());
                        Files.move(file.target(), file.source(), StandardCopyOption.ATOMIC_MOVE);
                    }
                } catch (Exception rollbackError) {
                    e.addSuppressed(rollbackError);
                }
            }
            throw new IllegalStateException("移动归属文件失败，已尝试回滚", e);
        }
    }

    OwnershipRepository repository() {
        return repository;
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
}
