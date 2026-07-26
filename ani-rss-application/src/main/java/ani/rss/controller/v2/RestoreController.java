package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.RestoreService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/v2/restore")
public class RestoreController {
    private final RestoreService restoreService;

    public RestoreController(RestoreService restoreService) {
        this.restoreService = restoreService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Auth
    public RestoreService.RestoreOperationView stage(@RequestParam("file") MultipartFile file) throws IOException {
        return restoreService.stage(file.getInputStream(), file.getSize());
    }

    @PostMapping("/{operationId}/confirm")
    @Auth
    public RestoreService.RestoreOperationView confirm(@PathVariable String operationId) {
        return restoreService.confirm(operationId);
    }

    @GetMapping("/{operationId}")
    @Auth
    public RestoreService.RestoreOperationView status(@PathVariable String operationId) {
        return restoreService.status(operationId);
    }
}
