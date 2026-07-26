package ani.rss.service;

import ani.rss.commons.PathPolicy;
import ani.rss.entity.Ani;
import ani.rss.util.other.AniUtil;
import ani.rss.util.other.ConfigUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Service
public class ClearService {
    private static final Set<String> COVER_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp");

    /**
     * 清理文件夹
     *
     * @param dir 文件夹
     */
    public void clearDir(String dir) {
        clearDir(new File(dir));
    }

    /**
     * 清理文件夹
     *
     * @param dir 文件夹
     */
    public void clearDir(File dir) {
        clearDir(dir, true, true, 2);
    }

    /**
     * 清理文件夹
     *
     * @param dir   文件夹
     * @param image 排除图片
     * @param nfo   排除nfo
     * @param max   向上删除深度
     */
    public void clearDir(File dir, boolean image, boolean nfo, int max) {
        if (dir == null || max <= 0) {
            return;
        }
        Path current = dir.toPath().toAbsolutePath().normalize();
        for (int i = 0; i < max; i++) {
            if (current == null || PathPolicy.isFileSystemRoot(current) ||
                    !Files.exists(current, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isSymbolicLink(current) ||
                    !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (!isTrulyEmpty(current)) {
                return;
            }
            try {
                Files.delete(current);
                log.info("清理空文件夹 {}", current);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (IOException e) {
                log.warn("清理空文件夹失败 path:{} type:{}", current, e.getClass().getSimpleName());
                return;
            }
            current = current.getParent();
        }
    }

    /**
     * 文件夹是否为空
     *
     * @param image 排除图片
     * @param nfo   排除nfo
     * @param dir   文件夹
     * @return 是否为空
     */
    public Boolean isEmpty(File dir, boolean image, boolean nfo) {
        if (dir == null) {
            return false;
        }
        Path path = dir.toPath().toAbsolutePath().normalize();
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(path) && isTrulyEmpty(path);
    }

    /**
     * 清理残余封面
     *
     * @return 清理大小 bytes
     */
    public Long clearCover() {
        Path configRoot = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path filesRoot = configRoot.resolve("files").normalize();
        prepareCacheDirectory(configRoot, filesRoot);

        Set<Path> covers = new HashSet<>();
        covers.add(filesRoot.resolve("cover.png").normalize());
        for (Ani ani : AniUtil.snapshot()) {
            String cover = ani.getCover();
            if (cover == null || cover.isBlank()) {
                continue;
            }
            try {
                covers.add(PathPolicy.resolveWithin(filesRoot, cover));
            } catch (RuntimeException e) {
                log.warn("忽略越界封面缓存引用 subscriptionId:{}", ani.getId());
            }
        }

        long deletedSize = 0;
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(filesRoot)) {
            for (Path directory : directories) {
                if (!isHashDirectory(directory)) {
                    continue;
                }
                deletedSize += clearHashDirectory(directory, covers);
                clearDir(directory.toFile(), false, false, 1);
            }
        } catch (IOException e) {
            throw new IllegalStateException("list cover cache failed", e);
        }
        return deletedSize;
    }

    public long clearPreviewImages() {
        Path configRoot = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path imageRoot = configRoot.resolve("img").normalize();
        prepareCacheDirectory(configRoot, imageRoot);
        long deletedSize = 0;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(imageRoot)) {
            for (Path entry : entries) {
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(entry)) {
                    log.warn("跳过非普通预览缓存 path:{}", entry);
                    continue;
                }
                long size = Files.size(entry);
                Files.delete(entry);
                deletedSize += size;
            }
        } catch (IOException e) {
            throw new IllegalStateException("clear preview image cache failed", e);
        }
        clearDir(imageRoot.toFile(), false, false, 1);
        return deletedSize;
    }

    private static long clearHashDirectory(Path directory, Set<Path> retained) throws IOException {
        long deletedSize = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                if (!isKnownCoverCacheFile(directory, file) || retained.contains(file.toAbsolutePath().normalize())) {
                    continue;
                }
                long size = Files.size(file);
                Files.delete(file);
                deletedSize += size;
            }
        }
        return deletedSize;
    }

    private static boolean isHashDirectory(Path path) {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return name.matches("[0-9a-fA-F]{2}");
    }

    private static boolean isKnownCoverCacheFile(Path directory, Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
            return false;
        }
        Path fileName = file.getFileName();
        Path directoryName = directory.getFileName();
        if (fileName == null || directoryName == null) {
            return false;
        }
        String name = fileName.toString();
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || !COVER_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        String hash = name.substring(0, dot);
        return hash.matches("[0-9a-fA-F]{64}") &&
                hash.regionMatches(true, 0, directoryName.toString(), 0, 2);
    }

    private static void prepareCacheDirectory(Path configRoot, Path cacheRoot) {
        if (!cacheRoot.startsWith(configRoot) || PathPolicy.isFileSystemRoot(cacheRoot)) {
            throw new IllegalStateException("cache path is outside the configuration root");
        }
        try {
            if (Files.exists(cacheRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(cacheRoot)) {
                throw new IllegalStateException("cache directory is a symbolic link");
            }
            Files.createDirectories(cacheRoot);
            PathPolicy.requireNoSymbolicLinks(configRoot, cacheRoot);
            PathPolicy.realPathWithin(configRoot, cacheRoot);
        } catch (IOException e) {
            throw new IllegalStateException("prepare cache directory failed", e);
        }
    }

    private static boolean isTrulyEmpty(Path directory) {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            return !entries.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

}
