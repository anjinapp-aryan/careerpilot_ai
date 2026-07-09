package ai.careerpilot.execution.api;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationTracking;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.tracking.ApplicationTrackingService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Phase 2E — the ONLY human-facing surface of the execution platform. HIGH RISK. Multi-tenant
 * isolation is enforced manually via the {@link ApprovalService} guards (repo convention — no
 * row-level security). The approve/reject endpoints are the sole trigger for
 * {@code ApprovalGrantedEvent}; there is no automatic path from a parked approval to execution.
 */
@RestController
@RequestMapping("/api/execution")
public class ExecutionController {

    private final ApprovalService approval;
    private final ApplicationExecutionService execution;
    private final ApplicationTrackingService tracking;

    public ExecutionController(ApprovalService approval, ApplicationExecutionService execution,
                               ApplicationTrackingService tracking) {
        this.approval = approval;
        this.execution = execution;
        this.tracking = tracking;
    }

    /** Pending approval queue for the current user. */
    @GetMapping("/pending")
    public List<ApprovalQueueEntry> pending(AuthenticatedUser user) {
        return approval.pending(user.userId());
    }

    /** Human approval — PENDING -> APPROVED, publishes ApprovalGrantedEvent. 409 if already decided. */
    @PostMapping("/approve/{id}")
    public ApprovalQueueEntry approve(AuthenticatedUser user, @PathVariable UUID id,
                                      @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return approval.approve(id, user.userId(), user.email(), note);
    }

    /** Human rejection — PENDING -> REJECTED, publishes nothing. 409 if already decided. */
    @PostMapping("/reject/{id}")
    public ApprovalQueueEntry reject(AuthenticatedUser user, @PathVariable UUID id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String note = body == null ? null : body.get("note");
        return approval.reject(id, user.userId(), user.email(), note);
    }

    /** Poll the status of a single execution attempt. */
    @GetMapping("/executions/{id}")
    public ApplicationExecution execution(AuthenticatedUser user, @PathVariable UUID id) {
        return execution.status(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("execution not found"));
    }

    /** Post-submission status timeline for a job's application (newest first). */
    @GetMapping("/tracking")
    public List<ApplicationTracking> tracking(AuthenticatedUser user, @RequestParam UUID jobId) {
        return tracking.timeline(user.userId(), jobId);
    }
}
