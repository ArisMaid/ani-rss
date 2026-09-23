package ani.rss.util.other;

import cn.hutool.core.codec.Base64;
import cn.hutool.crypto.SecureUtil;
import com.frostwire.jlibtorrent.SessionManager;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantLock;

/** Resolves collection inputs once so preview and submission use the same metadata. */
@Slf4j
public final class MagnetTorrentUtil {
    private static final ReentrantLock RESOLVE_LOCK = new ReentrantLock();
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private MagnetTorrentUtil() { }

    public static File sourceFile(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("请选择种子文件或输入磁力链接");
        }
        source = source.trim();
        if (source.length() > MAX_BYTES * 2) {
            throw new IllegalArgumentException("种子元数据过大");
        }
        boolean magnet = source.regionMatches(true, 0, "magnet:?", 0, 8);
        if (magnet && !source.matches("(?is).*xt=urn:bt(?:ih|mh):[^&]+.*")) {
            throw new IllegalArgumentException("磁力链接缺少有效的信息哈希");
        }
        if (!RESOLVE_LOCK.tryLock()) {
            throw new IllegalStateException("正在解析其他合集，请稍后重试");
        }
        try {
            Path directory = ConfigUtil.getConfigDir().toPath().resolve("cache/collection-metadata");
            Files.createDirectories(directory);
            Path cached = directory.resolve(SecureUtil.sha256(source) + ".torrent");
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) return cached.toFile();
            byte[] bytes = magnet ? fetch(source) : Base64.decode(source);
            if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IllegalArgumentException("种子元数据无效或过大");
            }
            Path temporary = Files.createTempFile(directory, "metadata-", ".tmp");
            try {
                Files.write(temporary, bytes);
                TorrentMetadata.from(temporary.toFile());
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
                return cached.toFile();
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (LinkageError e) {
            throw new IllegalStateException("当前平台不支持磁力元数据解析，请上传 .torrent 文件", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或保存种子元数据，请检查文件和缓存目录", e);
        } finally {
            RESOLVE_LOCK.unlock();
        }
    }

    private static byte[] fetch(String magnet) throws IOException {
        SessionManager session = new SessionManager();
        Path temporary = Files.createTempDirectory("ani-rss-magnet-");
        try {
            session.start();
            byte[] bytes = session.fetchMagnet(magnet, 60, temporary.toFile());
            if (bytes == null) {
                throw new IllegalStateException("磁力元数据获取超时，请检查网络或上传 .torrent 文件");
            }
            return bytes;
        } finally {
            try {
                session.stop();
            } finally {
                // Only remove an empty directory; never recursively delete native output.
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException e) {
                    log.warn("磁力临时目录未清空，请按需手动清理 type:{}", e.getClass().getSimpleName());
                }
            }
        }
    }
}
