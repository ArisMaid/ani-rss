package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.UploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/v2/uploads")
public class UploadV2Controller {
    private final UploadService uploadService;

    public UploadV2Controller(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    @Auth
    @PostMapping(value = "/cover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CoverUpload cover(@RequestParam("file") MultipartFile file) throws IOException {
        return new CoverUpload(uploadService.storeCover(file));
    }

    @Auth
    @PostMapping(value = "/torrent", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TorrentUpload torrent(@RequestParam("file") MultipartFile file) throws IOException {
        return new TorrentUpload(uploadService.encodeTorrent(file));
    }

    public record CoverUpload(String path) {
    }

    public record TorrentUpload(String base64) {
    }
}
