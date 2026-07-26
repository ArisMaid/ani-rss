package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.PathPolicy;
import ani.rss.entity.Global;
import ani.rss.entity.web.Header;
import ani.rss.util.other.ConfigUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.StrFormatter;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;

@Slf4j
@RestController
public class FileController extends BaseController {
    private static final Set<String> SAFE_IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp");

    @Auth
    @Operation(summary = "获取文件")
    @GetMapping("/file")
    public void file(@RequestParam("filename") String filename) {
        filename = filename.replace(" ", "+");
        filename = Base64.decodeStr(filename);

        verifyFileFormat(filename);
        doFile(filename);
    }

    /**
     * 校验文件格式
     */
    private void verifyFileFormat(String filename) {
        Assert.notBlank(filename, "不允许访问");

        String extName = FileUtil.extName(filename);

        Assert.notBlank(extName, "不允许访问");

        boolean b = SAFE_IMAGE_EXTENSIONS.contains(extName.toLowerCase());

        Assert.isTrue(b, "不允许访问");
    }

    /**
     * 处理文件
     *
     * @param filename 文件名
     */
    private void doFile(String filename) {
        HttpServletResponse response = Global.RESPONSE.get();

        File file;
        try {
            Path root = ConfigUtil.getConfigDir().toPath().toAbsolutePath().normalize().resolve("files");
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
                writeNotFound();
                return;
            }
            Path candidate = PathPolicy.resolveWithin(root, filename);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                writeNotFound();
                return;
            }
            PathPolicy.requireNoSymbolicLinks(root, candidate);
            Path real = PathPolicy.realPathWithin(root, candidate);
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(real)) {
                writeNotFound();
                return;
            }
            file = real.toFile();
        } catch (Exception e) {
            writeNotFound();
            return;
        }

        long fileLength = file.length();

        String contentType = getContentType(file.getName());

        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("inline; filename=\"{}\"", URLUtil.encode(file.getName())));
        long maxAge = 0;

        // 小于或者等于 3M 缓存
        if (fileLength <= 1024 * 1024 * 3) {
            // 30 天
            maxAge = 86400 * 30;
        }

        setCacheControl(response, maxAge);
        response.setContentType(contentType);

        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(file.length());

            @Cleanup
            InputStream inputStream = Files.newInputStream(
                    file.toPath(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            @Cleanup
            OutputStream outputStream = response.getOutputStream();
            IoUtil.copy(inputStream, outputStream);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.debug(message, e);
        }
    }

}
