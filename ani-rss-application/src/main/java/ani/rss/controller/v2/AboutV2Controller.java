package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.entity.About;
import ani.rss.service.UpdateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v2")
public class AboutV2Controller {
    private final UpdateService updateService;

    public AboutV2Controller(UpdateService updateService) {
        this.updateService = updateService;
    }

    @Auth
    @GetMapping("/about")
    public About about() {
        return updateService.about();
    }

    @Auth
    @PostMapping("/update")
    public ResponseEntity<UpdateAccepted> update() {
        About about = updateService.about();
        updateService.update(about);
        return ResponseEntity.accepted().body(new UpdateAccepted(about.getLatest(), "STARTED"));
    }

    public record UpdateAccepted(String version, String status) {
    }
}
