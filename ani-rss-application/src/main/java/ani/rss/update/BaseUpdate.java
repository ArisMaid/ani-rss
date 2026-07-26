package ani.rss.update;

import ani.rss.commons.MavenUtils;
import ani.rss.entity.About;
import ani.rss.util.basic.HttpReq;
import cn.hutool.core.lang.Assert;
import cn.hutool.system.OsInfo;
import cn.hutool.system.SystemUtil;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

@FunctionalInterface
public interface BaseUpdate {
    long MAX_UPDATE_BYTES = 512L * 1024 * 1024;
    Pattern SHA_256 = Pattern.compile("(?i)[0-9a-f]{64}");

    static BaseUpdate getInstance() {
        OsInfo osInfo = SystemUtil.getOsInfo();

        if (osInfo.isMac()) {
            return new MacUpdate();
        } else if (osInfo.isLinux()) {
            return new LinuxUpdate();
        } else if (osInfo.isWindows()) {
            return new WindowsUpdate();
        } else {
            String name = osInfo.getName();
            throw new IllegalArgumentException("不支持的系统 " + name);
        }
    }

    void update(File updateFile);

    default File downloadUpdateFile(About about) {
        validateMetadata(about);
        MavenUtils.CurrentFile currentFile = MavenUtils.getCurrentFile();
        String downloadUrl = about.getDownloadUrl();
        String sha256 = about.getSha256();
        long size = about.getSize();
        File currentArtifact = currentFile.getFile();
        if (currentArtifact == null) {
            throw new IllegalStateException("current application file is unavailable");
        }
        Path current = currentArtifact.toPath().toAbsolutePath().normalize();
        Path parent = current.getParent();
        Path directory = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
        Path temporary;
        try {
            temporary = Files.createTempFile(directory, ".ani-rss-update-", ".tmp");
        } catch (IOException e) {
            throw new IllegalStateException("create update temporary file failed", e);
        }

        boolean completed = false;
        try {
            HttpReq.get(downloadUrl)
                    .then(response -> {
                        HttpReq.assertStatus(response);
                        validateContentLength(response.header("Content-Length"), size);
                        try (InputStream input = response.bodyStream()) {
                            copyVerified(input, temporary, size, sha256);
                        } catch (IOException e) {
                            throw new IllegalStateException("read update response failed", e);
                        }
                    });
            completed = true;
            return temporary.toFile();
        } finally {
            if (!completed) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Leave only this exact partial file in place for manual inspection.
                }
            }
        }
    }

    static void validateMetadata(About about) {
        if (about == null) {
            throw new IllegalArgumentException("update metadata is required");
        }
        URI uri;
        try {
            uri = URI.create(about.getDownloadUrl());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("update URL is invalid", e);
        }
        Assert.isTrue("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null &&
                        uri.getUserInfo() == null && uri.getFragment() == null,
                "update URL must be HTTPS without credentials or fragment");
        Long declaredSize = about.getSize();
        Assert.isTrue(declaredSize != null && declaredSize > 0 && declaredSize <= MAX_UPDATE_BYTES,
                "update size is outside the allowed range");
        Assert.isTrue(about.getSha256() != null && SHA_256.matcher(about.getSha256()).matches(),
                "update SHA-256 is invalid");
    }

    static void validateContentLength(String value, long expectedSize) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            long contentLength = Long.parseLong(value.trim());
            Assert.isTrue(contentLength == expectedSize,
                    "update Content-Length does not match release metadata");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("update Content-Length is invalid", e);
        }
    }

    static void copyVerified(InputStream input, Path target, long expectedSize, String expectedSha256) {
        Assert.notNull(input, "update response stream is required");
        Assert.notNull(target, "update target is required");
        Assert.isTrue(expectedSize > 0 && expectedSize <= MAX_UPDATE_BYTES,
                "update size is outside the allowed range");
        Assert.isTrue(expectedSha256 != null && SHA_256.matcher(expectedSha256).matches(),
                "update SHA-256 is invalid");

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(target,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                Assert.isTrue(total <= expectedSize && total <= MAX_UPDATE_BYTES,
                        "update response exceeds the declared size");
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("write update temporary file failed", e);
        }

        Assert.isTrue(total == expectedSize, "update response is truncated");
        String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        Assert.isTrue(MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                        expectedSha256.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)),
                "update SHA-256 does not match release metadata");
    }
}
