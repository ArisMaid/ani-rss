package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.service.SubscriptionDeletionService;
import ani.rss.util.other.AniUtil;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    @PostMapping("/deletion-plans")
    public SubscriptionDeletionService.DeletionPlan planDeletion(@RequestBody DeletionPlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("deletion plan request is required");
        }
        return deletionService.plan(request.subscriptionIds(), request.deleteFiles());
    }

    @Auth
    @PostMapping("/deletion-plans/{operationId}/execute")
    public SubscriptionDeletionService.DeletionResult executeDeletion(@PathVariable String operationId) {
        return deletionService.execute(operationId);
    }

    @Auth
    @DeleteMapping("/deletion-plans/{operationId}")
    public void cancelDeletion(@PathVariable String operationId) {
        deletionService.cancel(operationId);
    }

    public record DeletionPlanRequest(List<String> subscriptionIds, boolean deleteFiles) {
        public DeletionPlanRequest {
            subscriptionIds = subscriptionIds == null ? List.of() : List.copyOf(subscriptionIds);
        }
    }
}
