package ai.careerpilot.execution.api;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationTracking;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.operations.OperationsService;
import ai.careerpilot.execution.tracking.ApplicationTrackingService;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
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
    private final OperationsService operations;

    @Value("${application.operations.enabled:false}") private boolean operationsEnabled;

    public ExecutionController(ApprovalService approval, ApplicationExecutionService execution,
                               ApplicationTrackingService tracking, OperationsService operations) {
        this.approval = approval;
        this.execution = execution;
        this.tracking = tracking;
        this.operations = operations;
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

    /**
     * Phase 7.16.3 — Recovery Center "Cancel Execution": human-initiated abort of a still-running
     * execution. 404 if not found/not owned by the caller; a no-op (still 200, {@code cancelled:false})
     * if the row is already terminal.
     */
    @PostMapping("/executions/{id}/cancel")
    public Map<String, Object> cancelExecution(AuthenticatedUser user, @PathVariable UUID id,
                                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null || body.get("reason") == null ? "no reason given" : body.get("reason");
        boolean cancelled = execution.cancel(id, user.userId(), reason);
        return Map.of("executionId", id, "cancelled", cancelled);
    }

    /**
     * Phase 7.16.3 — Recovery Center "Retry Failed" / "Retry Now": a human-initiated retry for an
     * execution parked at RETRY (normally picked up automatically by {@code RecoveryScheduler}) or
     * MANUAL_REVIEW (never auto-retried — this is the only way a PAUSE-decision execution resumes).
     * 409-equivalent (empty) if the execution isn't in a retryable state or isn't owned by the caller.
     */
    @PostMapping("/executions/{id}/retry-now")
    public ApplicationExecution retryNow(AuthenticatedUser user, @PathVariable UUID id) {
        return execution.status(id, user.userId())
                .flatMap(exec -> execution.retryExecution(id))
                .orElseThrow(() -> new NoSuchElementException("execution not found, not owned by caller, or not retryable"));
    }

    // ── Phase 7.16.4 — Application Detail Workspace / Evidence / Recovery / Explainability /
    // Timeline. All read-only aggregation over OperationsService, gated by
    // application.operations.enabled (404 when off, matching this controller's existing
    // authenticated-ownership convention rather than the diagnostics controller's {enabled:false}
    // shape, since these responses carry per-application content). ──

    @GetMapping("/executions/{id}/detail")
    public Map<String, Object> detail(AuthenticatedUser user, @PathVariable UUID id) {
        requireOperationsEnabled();
        return operations.detail(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("execution not found or not owned by caller"));
    }

    @GetMapping("/executions/{id}/explain")
    public Map<String, Object> explain(AuthenticatedUser user, @PathVariable UUID id) {
        requireOperationsEnabled();
        return operations.explain(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("execution not found or not owned by caller"));
    }

    /** User Action — "Download Evidence (JSON)". Same data as {@link #detail}'s evidence block, served as a file download. */
    @GetMapping("/executions/{id}/evidence")
    public ResponseEntity<Map<String, Object>> evidence(AuthenticatedUser user, @PathVariable UUID id) {
        requireOperationsEnabled();
        Map<String, Object> full = operations.detail(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("execution not found or not owned by caller"));
        Map<String, Object> body = Map.of(
                "executionId", id,
                "evidence", full.get("evidence"),
                "auditTrail", full.get("auditTrail"),
                "screenshots", full.get("screenshots"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"evidence-" + id + ".json\"")
                .body(body);
    }

    /** User Action — "Export Timeline (JSON)". */
    @GetMapping("/executions/{id}/timeline-export")
    public ResponseEntity<Map<String, Object>> timelineExport(AuthenticatedUser user, @PathVariable UUID id) {
        requireOperationsEnabled();
        Map<String, Object> full = operations.detail(id, user.userId())
                .orElseThrow(() -> new NoSuchElementException("execution not found or not owned by caller"));
        Map<String, Object> body = Map.of("executionId", id, "timeline", full.get("timeline"));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"timeline-" + id + ".json\"")
                .body(body);
    }

    /**
     * User Action — "Request Manual Review": a human-initiated escalation of a still-running
     * execution straight to {@code STATUS_MANUAL_REVIEW}, distinct from the automatic PAUSE decision
     * {@code AutomationRecoveryService} makes on a CAPTCHA/confirmation-missing failure. Refuses on
     * an already-terminal row, same guard shape as {@link #cancelExecution}.
     */
    @PostMapping("/executions/{id}/manual-review")
    public Map<String, Object> requestManualReview(AuthenticatedUser user, @PathVariable UUID id,
                                                    @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null || body.get("reason") == null ? "manual review requested by user" : body.get("reason");
        boolean requested = execution.requestManualReview(id, user.userId(), reason);
        return Map.of("executionId", id, "requested", requested);
    }

    private void requireOperationsEnabled() {
        if (!operationsEnabled) {
            throw new NoSuchElementException("operations center is not enabled");
        }
    }

    /** Post-submission status timeline for a job's application (newest first). */
    @GetMapping("/tracking")
    public List<ApplicationTracking> tracking(AuthenticatedUser user, @RequestParam UUID jobId) {
        return tracking.timeline(user.userId(), jobId);
    }
}
