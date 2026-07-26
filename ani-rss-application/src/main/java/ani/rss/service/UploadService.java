package ani.rss.service;

import ani.rss.commons.PathPolicy;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.crypto.SecureUtil;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.eclipse.bittorrent.TorrentFile;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

@Service
public class UploadService {
    private static final long MAX_COVER_BYTES = 1024L * 1024;
    private static final long MAX_TORRENT_BYTES = 5L * 1024 * 1024;

    public String encodeTorrent(MultipartFile file) throws IOException {
        byte[] content = readBounded(file, MAX_TORRENT_BYTES);
        String filename = file.getOriginalFilename();
        if (filename == null || !"torrent".equalsIgnoreCase(FileUtil.extName(filename)) ||
                content.length < 2 || content[0] != 'd' || content[content.length - 1] != 'e') {
            throw new IllegalArgumentException("upload is not a torrent file");
        }
        validateTorrent(content);
        return Base64.encode(content);
    }

    public String storeCover(MultipartFile file) throws IOException {
        byte[] content = readBounded(file, MAX_COVER_BYTES);
        String contentType = SafeImageFetcher.validateRaster(content);
        if (!"image/jpeg".equals(contentType) && !"image/png".equals(contentType)) {
            throw new IllegalArgumentException("cover must be a JPEG or PNG image");
        }
        String extension = "image/png".equals(contentType) ? ".png" : ".jpg";
        String digest = SecureUtil.sha256(new ByteArrayInputStream(content));
        String directory = digest.substring(0, 2).toLowerCase(Locale.ROOT);
        String saveName = digest + extension;
        Path configRoot = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path filesRoot = configRoot.resolve("files");
        Path targetDirectory = filesRoot.resolve(directory);
        prepareDirectory(configRoot, filesRoot, targetDirectory);
        Path target = targetDirectory.resolve(saveName);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verifyExistingTarget(target, digest);
        } else {
            writeNew(targetDirectory, target, content, digest);
        }
        return directory + "/" + saveName;
    }

    private static byte[] readBounded(MultipartFile file, long limit) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("upload is empty");
        }
        if (file.getSize() < 0 || file.getSize() > limit) {
            throw new IllegalArgumentException("upload exceeds the allowed size");
        }
        try (InputStream input = file.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     (int) Math.min(Math.max(file.getSize(), 0), limit))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > limit) {
                    throw new IllegalArgumentException("upload exceeds the allowed size");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void validateTorrent(byte[] content) throws IOException {
        Path temporary = Files.createTempFile("ani-rss-upload-", ".torrent");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                synchronized (TorrentFile.class) {
                    new TorrentFile(temporary.toFile());
                }
            } catch (RuntimeException | IOException e) {
                throw new IllegalArgumentException("upload is not a valid torrent file", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void prepareDirectory(Path configRoot, Path filesRoot, Path targetDirectory) throws IOException {
        Files.createDirectories(configRoot);
        if (Files.exists(filesRoot, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(filesRoot)) {
            throw new IOException("upload directory cannot be a symbolic link");
        }
        Files.createDirectories(filesRoot);
        if (Files.exists(targetDirectory, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(targetDirectory)) {
            throw new IOException("upload directory cannot be a symbolic link");
        }
        Files.createDirectories(targetDirectory);
        PathPolicy.requireNoSymbolicLinks(configRoot, targetDirectory);
        PathPolicy.realPathWithin(configRoot, targetDirectory);
    }

    private static void writeNew(Path directory, Path target, byte[] content, String digest) throws IOException {
        Path temporary = Files.createTempFile(directory, ".cover-", ".part");
        try {
            Files.write(temporary, content, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException e) {
                verifyExistingTarget(target, digest);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyExistingTarget(Path target, String expectedDigest) throws IOException {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target) ||
                !expectedDigest.equalsIgnoreCase(SecureUtil.sha256(target.toFile()))) {
            throw new IOException("upload target conflicts with existing file");
        }
    }
}
