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
        return deletionService.delete(request.subscriptionIds(), request.deleteFilesOrDefault());
    }

    public record DeletionRequest(List<String> subscriptionIds, Boolean deleteFiles) {
        public DeletionRequest {
            subscriptionIds = subscriptionIds == null ? List.of() : List.copyOf(subscriptionIds);
        }

        public boolean deleteFilesOrDefault() {
            // Preserve the direct-delete behavior for older clients that do
            // not yet send the new explicit checkbox value.
            return !Boolean.FALSE.equals(deleteFiles);
        }
    }
}
