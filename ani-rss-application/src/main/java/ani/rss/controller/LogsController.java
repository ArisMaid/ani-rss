package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.Log;
import ani.rss.entity.web.Header;
import ani.rss.entity.web.Result;
import ani.rss.commons.PathPolicy;
import ani.rss.util.basic.LogUtil;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.text.StrFormatter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@RestController
public class LogsController extends BaseController {
    @Auth
    @Operation(summary = "日志")
    @PostMapping("/logs")
    public Result<List<Log>> logs() {
        return Result.success(LogUtil.snapshot());
    }

    @Auth
    @Operation(summary = "清理日志")
    @PostMapping("/clearLogs")
    public Result<Void> clearLogs() {
        LogUtil.clear();
        log.info("清理日志");
        return Result.success();
    }

    @Auth
    @Operation(summary = "下载日志")
    @GetMapping("/downloadLogs")
    public void downloadLogs() throws IOException {
        Path configRoot = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize();
        Path logsRoot = configRoot.resolve("logs");
        if (!Files.isDirectory(logsRoot, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(logsRoot)) {
            throw new IllegalStateException("log directory is unavailable");
        }
        PathPolicy.requireNoSymbolicLinks(configRoot, logsRoot);
        Path realLogsRoot = PathPolicy.realPathWithin(configRoot, logsRoot);

        String filename = "logs.zip";

        String contentType = getContentType(filename);

        HttpServletResponse response = Global.RESPONSE.get();

        response.setContentType(contentType);
        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("attachment; filename=\"{}\"", filename));
        response.setHeader("Cache-Control", "no-store");

        try (ZipOutputStream zip = new ZipOutputStream(response.getOutputStream(), StandardCharsets.UTF_8);
             var paths = Files.list(realLogsRoot)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                Path pathName = path.getFileName();
                if (pathName == null || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(path) ||
                        !pathName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".log")) {
                    continue;
                }
                PathPolicy.requireNoSymbolicLinks(realLogsRoot, path);
                Path real = PathPolicy.realPathWithin(realLogsRoot, path);
                Path realName = real.getFileName();
                if (realName == null) {
                    throw new IllegalStateException("log file path has no file name");
                }
                zip.putNextEntry(new ZipEntry(realName.toString()));
                try (InputStream input = Files.newInputStream(
                        real, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    input.transferTo(zip);
                }
                zip.closeEntry();
            }
        }
    }
}
