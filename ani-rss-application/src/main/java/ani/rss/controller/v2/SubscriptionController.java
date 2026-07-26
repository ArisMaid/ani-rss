package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.SubscriptionDeletionService;
import ani.rss.util.other.AniUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v2/subscriptions")
public class SubscriptionController {
    private final SubscriptionDeletionService deletionService;

    public SubscriptionController(SubscriptionDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @Auth
    @GetMapping
    public List<SubscriptionDeletionService.SubscriptionSummary> list() {
        return AniUtil.snapshot().stream()
                .map(ani -> new SubscriptionDeletionService.SubscriptionSummary(
                        ani.getId(), ani.getTitle(), ani.getSeason()))
                .toList();
    }

    @Auth
    @PostMapping("/delete")
    public SubscriptionDeletionService.DeletionResult delete(@RequestBody DeletionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("subscription deletion request is required");
        }
        return deletionService.delete(request.subscriptionIds(), true);
    }

    public record DeletionRequest(List<String> subscriptionIds) {
        public DeletionRequest {
            subscriptionIds = subscriptionIds == null ? List.of() : List.copyOf(subscriptionIds);
        }
    }
}
