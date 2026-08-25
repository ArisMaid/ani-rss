package ani.rss.service;

import ani.rss.commons.GsonStatic;
import ani.rss.entity.UpdateInfo;
import ani.rss.entity.WebUI;
import ani.rss.util.basic.HttpReq;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebUIService {
    private static final long MAX_ARCHIVE_BYTES = 50L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10_000;

    private final GithubService githubService;

    public Path getWebUIDir() {
        return ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve("webui");
    }

    public WebUI getWebUI() {
        Path metadata = getWebUIDir().resolve("webui.json");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            WebUI webUI = GsonStatic.fromJson(Files.readString(metadata), WebUI.class);
            if (webUI == null || !safeToken(webUI.getOwner()) || !safeToken(webUI.getRepo()) ||
                    !safeToken(webUI.getVersion()) || !safeToken(webUI.getFilename())) {
                return null;
            }
            return webUI;
        } catch (Exception e) {
            log.warn("读取 WebUI 元数据失败 type:{}", e.getClass().getSimpleName());
            return null;
        }
    }

    public UpdateInfo getUpdate() {
        WebUI webUI = getWebUI();
        if (webUI == null) {
            return null;
        }
        return githubService.getUpdateInfo(webUI.getOwner(), webUI.getRepo(),
                webUI.getFilename(), webUI.getVersion());
    }

    public void upload(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_ARCHIVE_BYTES ||
                file.getOriginalFilename() == null ||
                !"zip".equalsIgnoreCase(extension(file.getOriginalFilename()))) {
            throw new IllegalArgumentException("WebUI 必须是 50 MiB 以内的 ZIP 文件");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("ani-rss-webui-", ".zip");
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            }
            Path staging = extractAndValidate(temporary);
            replace(staging);
        } catch (IOException e) {
            throw new IllegalArgumentException("上传 WebUI 失败", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public void update() {
        UpdateInfo update = getUpdate();
        if (update == null || !Boolean.TRUE.equals(update.getUpdate())) {
            throw new IllegalStateException("无 WebUI 更新");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile("ani-rss-webui-", ".zip");
            Path target = temporary;
            HttpReq.get(update.getDownloadUrl()).then(response -> {
                HttpReq.assertStatus(response);
                try (InputStream input = response.bodyStream()) {
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new IllegalStateException("下载 WebUI 失败", e);
                }
            });
            if (Files.size(temporary) > MAX_ARCHIVE_BYTES ||
                    Files.size(temporary) != update.getSize() ||
                    !SecureUtil.sha256(temporary.toFile()).equalsIgnoreCase(update.getSha256())) {
                throw new IllegalStateException("WebUI 更新文件校验失败");
            }
            Path staging = extractAndValidate(temporary);
            replace(staging);
        } catch (IOException e) {
            throw new IllegalStateException("更新 WebUI 失败", e);
        } finally {
            deleteQuietly(temporary);
        }
    }

    public void delete() {
        Path webui = getWebUIDir();
        try {
            deleteTree(webui);
        } catch (IOException e) {
            throw new IllegalStateException("删除 WebUI 失败", e);
        }
    }

    private Path extractAndValidate(Path archive) throws IOException {
        Path configDir = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Files.createDirectories(configDir);
        Path staging = Files.createTempDirectory(configDir, ".webui-stage-");
        long extractedBytes = 0;
        int entries = 0;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IllegalArgumentException("WebUI 文件数量过多");
                }
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) {
                    name = name.replaceFirst("/+$", "");
                }
                String[] segments = name.split("/", -1);
                boolean unsafeSegment = java.util.Arrays.stream(segments)
                        .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals(".."));
                if (name.isBlank() || name.startsWith("/") || name.matches("^[A-Za-z]:.*") ||
                        name.contains("\0") || unsafeSegment) {
                    throw new IllegalArgumentException("WebUI ZIP 路径非法");
                }
                Path target = staging.resolve(name).normalize();
                if (!target.startsWith(staging)) {
                    throw new IllegalArgumentException("WebUI ZIP 路径越界");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(Objects.requireNonNull(target.getParent()));
                try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read == 0) continue;
                        extractedBytes += read;
                        if (extractedBytes > MAX_EXTRACTED_BYTES) {
                            throw new IllegalArgumentException("WebUI 解压内容过大");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        } catch (Exception e) {
            deleteTree(staging);
            if (e instanceof IOException io) throw io;
            if (e instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("WebUI ZIP 无效", e);
        }
        Path metadata = staging.resolve("webui.json");
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            deleteTree(staging);
            throw new IllegalArgumentException("WebUI ZIP 缺少 webui.json");
        }
        try {
            WebUI webUI = GsonStatic.fromJson(Files.readString(metadata), WebUI.class);
            if (webUI == null || !safeToken(webUI.getOwner()) || !safeToken(webUI.getRepo()) ||
                    !safeToken(webUI.getVersion()) || !safeToken(webUI.getFilename())) {
                throw new IllegalArgumentException("WebUI 元数据无效");
            }
        } catch (Exception e) {
            deleteTree(staging);
            if (e instanceof IllegalArgumentException argument) throw argument;
            throw new IllegalArgumentException("WebUI 元数据无效", e);
        }
        return staging;
    }

    private void replace(Path staging) throws IOException {
        Path target = getWebUIDir();
        Files.createDirectories(target.getParent());
        deleteTree(target);
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(staging, target);
        }
    }

    private static boolean safeToken(String value) {
        return StrUtil.isNotBlank(value) && value.length() <= 200 &&
                !value.contains("/") && !value.contains("\\") && !value.contains("..") &&
                !value.contains("\0");
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            deleteTree(path);
        } catch (IOException ignored) {
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path);
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(value -> {
                try {
                    Files.deleteIfExists(value);
                } catch (IOException e) {
                    throw new DeleteFailure(e);
                }
            });
        } catch (DeleteFailure e) {
            throw e.cause;
        }
    }

    private static final class DeleteFailure extends RuntimeException {
        private final IOException cause;
        private DeleteFailure(IOException cause) { this.cause = cause; }
    }
}
