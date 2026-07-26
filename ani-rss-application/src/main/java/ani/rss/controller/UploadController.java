package ani.rss.controller;

import ani.rss.annotation.Auth;
import ani.rss.entity.Global;
import ani.rss.entity.web.Result;
import ani.rss.service.UploadService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
public class UploadController extends BaseController {
    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Auth
    @Operation(summary = "上传文件")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        HttpServletRequest request = Global.REQUEST.get();
        if (request != null && "getBase64".equals(request.getParameter("type"))) {
            return Result.success(uploadService.encodeTorrent(file));
        }
        return Result.success(uploadService.storeCover(file));
    }
}
