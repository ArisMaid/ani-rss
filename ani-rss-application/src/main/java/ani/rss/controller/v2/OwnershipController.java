package ani.rss.controller.v2;

import ani.rss.annotation.Auth;
import ani.rss.ownership.DownloadOwnership;
import ani.rss.ownership.OwnershipCandidate;
import ani.rss.ownership.OwnershipMigrationService;
import ani.rss.ownership.OwnershipService;
import ani.rss.ownership.QuarantineEntry;
import ani.rss.ownership.QuarantineService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v2/ownership")
public class OwnershipController {
    private final OwnershipService ownershipService;
    private final OwnershipMigrationService migrationService;
    private final QuarantineService quarantineService;

    public OwnershipController(
            OwnershipService ownershipService,
            OwnershipMigrationService migrationService,
            QuarantineService quarantineService) {
        this.ownershipService = ownershipService;
        this.migrationService = migrationService;
        this.quarantineService = quarantineService;
    }

    @GetMapping
    @Auth
    public List<DownloadOwnership> list() {
        return ownershipService.listAll();
    }

    @GetMapping("/candidates")
    @Auth
    public List<OwnershipCandidate> candidates() {
        return migrationService.scan();
    }

    @PostMapping("/adopt")
    @Auth
    public DownloadOwnership adopt(@RequestBody AdoptRequest request) {
        return migrationService.adopt(
                request.remoteTaskId(),
                request.infoHash(),
                request.subscriptionId(),
                request.confirmed());
    }

    @GetMapping("/quarantine")
    @Auth
    public List<QuarantineEntry> quarantine() {
        return quarantineService.list();
    }

    @PostMapping("/quarantine/restore")
    @Auth
    public void restore(@RequestParam String operationId) {
        quarantineService.restore(operationId);
    }

    @PostMapping("/quarantine/plans")
    @Auth
    public QuarantineService.DestructiveOperationPlan plan(@RequestBody QuarantinePlanRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("quarantine plan request is required");
        }
        return quarantineService.planOwnerships(request.ownershipIds());
    }

    @PostMapping("/quarantine/plans/{operationId}/execute")
    @Auth
    public OperationResult executePlan(@PathVariable String operationId) {
        return new OperationResult(quarantineService.executePlan(operationId));
    }

    @DeleteMapping("/quarantine/plans/{operationId}")
    @Auth
    public void cancelPlan(@PathVariable String operationId) {
        quarantineService.cancelPlan(operationId);
    }

    @PostMapping("/quarantine/purge-expired")
    @Auth
    public PurgeResult purgeExpired() {
        return new PurgeResult(quarantineService.purgeExpired());
    }

    @PostMapping("/quarantine/purge")
    @Auth
    public PurgeResult purge(@RequestBody PurgeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("purge request is required");
        }
        return new PurgeResult(quarantineService.purge(request.operationId(), request.confirmed()));
    }

    public record AdoptRequest(
            String remoteTaskId,
            String infoHash,
            String subscriptionId,
            boolean confirmed) {
    }

    public record PurgeResult(int purged) {
    }

    public record QuarantinePlanRequest(List<String> ownershipIds) {
        public QuarantinePlanRequest {
            ownershipIds = ownershipIds == null ? List.of() : List.copyOf(ownershipIds);
        }
    }

    public record PurgeRequest(String operationId, boolean confirmed) {
    }

    public record OperationResult(String operationId) {
    }
}
