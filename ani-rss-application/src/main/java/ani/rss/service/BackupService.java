package ani.rss.service;

import ani.rss.backup.BackupArchive;
import ani.rss.commons.MavenUtils;
import ani.rss.util.other.ConfigUtil;
import ani.rss.util.other.AniUtil;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BackupService {
    private final ClearService clearService;

    public BackupService(ClearService clearService) {
        this.clearService = clearService;
    }

    public synchronized void backup() {
        if (!Boolean.TRUE.equals(ConfigUtil.snapshot().getConfigBackup())) {
            return;
        }
        clearBackup();
        Path configDir = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path backupDir = configDir.resolve("backup");
        String date = DateUtil.format(new Date(), DatePattern.NORM_DATE_PATTERN);
        Path backupFile = backupDir.resolve(date + ".zip");
        if (Files.exists(backupFile, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path temporary = backupDir.resolve("." + date + "." + UUID.randomUUID() + ".tmp");
        log.info("正在备份设置 {}", backupFile.getFileName());
        try {
            Files.createDirectories(backupDir);
            try (FileChannel channel = FileChannel.open(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                OutputStream output = java.nio.channels.Channels.newOutputStream(channel);
                backup(output);
                channel.force(true);
            }
            Files.move(temporary, backupFile, StandardCopyOption.ATOMIC_MOVE);
            log.info("备份设置成功 {}", backupFile.getFileName());
        } catch (Exception e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            log.error("备份失败 {}", backupFile.getFileName(), e);
            throw new IllegalStateException("backup failed", e);
        }
    }

    /** Writes a manifest-backed archive without closing the caller-owned stream. */
    public synchronized void backup(OutputStream outputStream) throws IOException {
        clearService.clearCover();
        BackupArchive.create(outputStream,
                ConfigUtil.getConfigDir().toPath(),
                MavenUtils.getVersion());
    }

    public synchronized void clearBackup() {
        Integer days = ConfigUtil.snapshot().getConfigBackupDay();
        long retentionMillis = TimeUnit.DAYS.toMillis(Math.max(1L, days == null ? 7L : days.longValue()));
        long now = System.currentTimeMillis();
        long expiration = now - retentionMillis;
        Path backupDir = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve("backup");
        if (!Files.isDirectory(backupDir, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(backupDir)) {
            return;
        }
        try (var files = Files.list(backupDir)) {
            files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> "zip".equalsIgnoreCase(FileUtil.extName(path.toString())))
                    .forEach(path -> removeExpired(path, expiration));
        } catch (IOException e) {
            throw new IllegalStateException("list backups failed", e);
        }
    }

    private void removeExpired(Path file, long expiration) {
        try {
            long timestamp = DateUtil.parse(FileUtil.mainName(file.toString()), DatePattern.NORM_DATE_PATTERN).getTime();
            if (timestamp <= expiration) {
                Files.deleteIfExists(file);
                log.info("备份已过期，自动删除 {}", file.getFileName());
            }
        } catch (Exception e) {
            log.warn("skip invalid backup filename {}", file.getFileName());
        }
    }
}
