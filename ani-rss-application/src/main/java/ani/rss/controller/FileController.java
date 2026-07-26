package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.commons.ExceptionUtils;
import ani.rss.commons.ByteRange;
import ani.rss.commons.FileUtils;
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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Cleanup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

@Slf4j
@RestController
public class FileController extends BaseController {

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

        boolean b = FileUtils.isImageFormat(filename) ||
                FileUtils.isSubtitleFormat(filename) ||
                FileUtils.isVideoFormat(filename);

        Assert.isTrue(b, "不允许访问");
    }

    /**
     * 处理文件
     *
     * @param filename 文件名
     */
    private void doFile(String filename) {
        HttpServletRequest request = Global.REQUEST.get();
        HttpServletResponse response = Global.RESPONSE.get();

        File file = new File(filename);
        if (!file.exists()) {
            File configDir = ConfigUtil.getConfigDir();
            file = Path.of(configDir.toString(), "files", filename).toFile();
            if (!file.exists()) {
                writeNotFound();
                return;
            }
        }

        long fileLength = file.length();

        String contentType = getContentType(file.getName());

        response.setHeader(Header.CONTENT_DISPOSITION, StrFormatter.format("inline; filename=\"{}\"", URLUtil.encode(file.getName())));
        if (contentType.startsWith("video/")) {
            response.setContentType(contentType);
            response.setHeader(Header.ACCEPT_RANGES, "bytes");
            String rangeHeader = request.getHeader("Range");
            if (StrUtil.isNotBlank(rangeHeader)) {
                writeRange(file, rangeHeader, response);
                return;
            }
        } else {
            long maxAge = 0;

            // 小于或者等于 3M 缓存
            if (fileLength <= 1024 * 1024 * 3) {
                // 30 天
                maxAge = 86400 * 30;
            }

            setCacheControl(response, maxAge);
            response.setContentType(contentType);
        }

        try {
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentLengthLong(file.length());

            @Cleanup
            InputStream inputStream = FileUtil.getInputStream(file);
            @Cleanup
            OutputStream outputStream = response.getOutputStream();
            IoUtil.copy(inputStream, outputStream);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.debug(message, e);
        }
    }

    private void writeRange(File file, String rangeHeader, HttpServletResponse response) {
        try {
            ByteRange range = ByteRange.parseSingle(rangeHeader, file.length());
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(Header.CONTENT_RANGE, range.contentRange());
            response.setContentLengthLong(range.length());

            @Cleanup
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            randomAccessFile.seek(range.start());
            @Cleanup
            FileChannel channel = randomAccessFile.getChannel();
            @Cleanup
            InputStream inputStream = Channels.newInputStream(channel);
            @Cleanup
            OutputStream outputStream = response.getOutputStream();
            IoUtil.copy(inputStream, outputStream, 40960, range.length(), null);
        } catch (ByteRange.MalformedRangeException | ByteRange.UnsatisfiedRangeException e) {
            response.resetBuffer();
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader(Header.CONTENT_RANGE, "bytes */" + file.length());
            response.setContentLengthLong(0);
        } catch (Exception e) {
            String message = ExceptionUtils.getMessage(e);
            log.debug(message, e);
        }
    }
}
