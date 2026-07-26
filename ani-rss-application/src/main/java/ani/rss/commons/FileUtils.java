package ani.rss.commons;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Slf4j
public class FileUtils {
    /**
     * 视频格式
     */
    private static final Set<String> VIDEO_FORMAT = Set.of("mp4", "mkv", "avi", "wmv");

    /**
     * 字幕格式
     */
    private static final Set<String> SUBTITLE_FORMAT = Set.of("ass", "ssa", "sub", "srt", "lyc", "sup", "pgs", "mks");

    /**
     * 图片格式
     */
    private static final Set<String> IMAGE_FORMAT = Set.of("png", "jpg", "jpeg", "webp", "svg");

    /**
     * 判断文件名是否为视频
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isVideoFormat(String filename) {
        return isFormat(filename, VIDEO_FORMAT);
    }

    /**
     * 判断文件名是否为字幕
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isSubtitleFormat(String filename) {
        return isFormat(filename, SUBTITLE_FORMAT);
    }

    /**
     * 判断文件名是否为图片
     *
     * @param filename 文件名
     * @return 是/否
     */
    public static Boolean isImageFormat(String filename) {
        return isFormat(filename, IMAGE_FORMAT);
    }

    public static Boolean isFormat(String filename, Set<String> extNames) {
        if (StrUtil.isBlank(filename)) {
            return false;
        }
        filename = filename.toLowerCase();

        String extName = FileUtil.extName(filename);
        if (StrUtil.isNotBlank(extName)) {
            return extNames.contains(extName);
        }

        return extNames.contains(filename);
    }

    /**
     * 获取绝对路径 并把 windows 狗日的 \ 转换为 /
     *
     * @param file 文件
     * @return 绝对路径
     */
    public static String getAbsolutePath(File file) {
        String absolutePath = file.getPath();
        if (absolutePath.startsWith("/")) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        if (ReUtil.contains("^[A-z]:", absolutePath)) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        absolutePath = file.getAbsolutePath();
        return normalize(absolutePath);
    }

    /**
     * 获取绝对路径 并把 windows 狗日的 \ 转换为 /
     *
     * @param absolutePath 路径
     * @return 绝对路径
     */
    public static String getAbsolutePath(String absolutePath) {
        if (absolutePath.startsWith("/")) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        if (ReUtil.contains("^[A-z]:", absolutePath)) {
            // 已是绝对路径
            return normalize(absolutePath);
        }

        absolutePath = new File(absolutePath).getAbsolutePath();
        return normalize(absolutePath);
    }

    public static String normalize(String path) {
        return PathPolicy.normalize(path);
    }

    /**
     * 获取文件列表 不会存在空指针问题
     *
     * @param path 文件夹位置
     * @return 文件列表
     */
    public static File[] listFiles(String path) {
        return listFiles(new File(path));
    }

    /**
     * 获取文件列表 不会存在空指针问题
     *
     * @param file 文件夹位置
     * @return 文件列表
     */
    public static File[] listFiles(File file) {
        if (Objects.isNull(file)) {
            return new File[0];
        }
        if (!file.exists()) {
            return new File[0];
        }
        if (file.isDirectory()) {
            return ObjectUtil.defaultIfNull(file.listFiles(), new File[0]);
        }
        return new File[0];
    }

    public static List<File> listFileList(File file) {
        return List.of(listFiles(file));
    }

    public static List<File> listFileList(String path) {
        return listFileList(new File(path));
    }

    /**
     * 文件移动 优先尝试原子移动
     *
     * @param source 原位置
     * @param target 目标位置
     */
    public static void move(Path source, Path target) {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("move source does not exist");
        }
        if (Files.isSymbolicLink(normalizedSource)) {
            throw new IllegalStateException("move source is a symbolic link");
        }
        if (Files.exists(normalizedTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("move target already exists; overwrite was refused");
        }
        Path targetParent = normalizedTarget.getParent();
        if (targetParent == null || Files.isSymbolicLink(targetParent)) {
            throw new IllegalStateException("move target parent is unsafe");
        }
        try {
            Files.move(normalizedSource, normalizedTarget, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            try {
                Files.move(normalizedSource, normalizedTarget);
            } catch (IOException fallbackFailure) {
                fallbackFailure.addSuppressed(e);
                throw new IllegalStateException("move file failed", fallbackFailure);
            }
        } catch (IOException e) {
            throw new IllegalStateException("move file failed", e);
        }
    }

    public static boolean deleteRegularFile(File file) {
        Objects.requireNonNull(file, "file");
        return deleteRegularFile(file.toPath());
    }

    public static boolean deleteRegularFile(Path path) {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        try {
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.isSymbolicLink(normalized) ||
                    !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("refusing to delete a non-regular or symbolic file");
            }
            Files.delete(normalized);
            return true;
        } catch (IOException e) {
            throw new IllegalStateException("delete regular file failed", e);
        }
    }

    public static String formatSize(File file) {
        return formatSize(file.length(), true);
    }

    public static String formatSize(File file, boolean use1024) {
        Assert.isTrue(file.exists(), "文件不存在 {}", file);
        return formatSize(file.length(), use1024);
    }

    public static String formatSize(long size, boolean use1024) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }

        int base = use1024 ? 1024 : 1000;
        double value = size;

        String[] units = use1024
                ? new String[]{"B", "KiB", "MiB", "GiB", "TiB"}
                : new String[]{"B", "KB", "MB", "GB", "TB"};

        int index = 0;
        while (value >= base && index < units.length - 1) {
            value /= base;
            index++;
        }

        // 保留最多两位小数
        return String.format("%.2f %s", value, units[index]);
    }
}
