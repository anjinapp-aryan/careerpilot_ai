package ai.careerpilot.execution.approval;

import ai.careerpilot.domain.ApprovalAuditEntry;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.repo.ApprovalAuditRepository;
import ai.careerpilot.repo.ApprovalQueueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2E.4 — the MANDATORY human approval gate between safety validation and execution. HIGH RISK.
 *
 * <p>{@link #enqueue} parks a PENDING row (called by {@code ApprovalWorker} off a
 * {@code SafetyValidatedEvent}). {@link #approve} / {@link #reject} are driven ONLY by the human
 * {@code /api/execution/approve|reject} endpoints. Only {@link #approve} publishes
 * {@link ApprovalGrantedEvent} — there is no automatic publisher anywhere, so nothing downstream
 * executes without a human decision.
 *
 * <p><b>Double-guard:</b> a row may transition out of PENDING exactly once. Approving/rejecting an
 * already-terminal row throws {@link IllegalStateException} (mapped to HTTP 409 by
 * {@code GlobalExceptionHandler}) — mirroring the workflow human-approval guard so a terminal
 * decision can never be silently overwritten.
 *
 * <p>Flag-gated by {@code application.approval.enabled} (default <b>true</b> — approval is the
 * safety default; disabling it does not create an auto-approve path, it simply stops enqueueing).
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ApprovalQueueRepository queue;
    private final ApprovalAuditRepository audit;
    private final ApprovalMetrics metrics;
    private final ApplicationEventPublisher events;
    private final boolean enabled;

    public ApprovalService(ApprovalQueueRepository queue, ApprovalAuditRepository audit,
                           ApprovalMetrics metrics, ApplicationEventPublisher events,
                           @Value("${application.approval.enabled:true}") boolean enabled) {
        this.queue = queue;
        this.audit = audit;
        this.metrics = metrics;
        this.events = events;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Park a PENDING approval request. Empty when disabled. Never throws. */
    @Transactional
    public Optional<ApprovalQueueEntry> enqueue(UUID userId, UUID jobId, UUID applicationPackageId, String verdict) {
        if (!enabled) return Optional.empty();
        try {
            ApprovalQueueEntry entry = queue.save(ApprovalQueueEntry.builder()
                    .userId(userId).jobId(jobId)
                    .applicationPackageId(applicationPackageId)
                    .safetyVerdict(verdict)
                    .status(ApprovalQueueEntry.STATUS_PENDING)
                    .approvalType(ApprovalQueueEntry.TYPE_APPLICATION_PACKAGE)
                    .build());
            metrics.recordEnqueued();
            record(userId, jobId, entry.getId(), ApprovalAuditEntry.OUTCOME_ENQUEUED, "verdict=" + verdict);
            log.info("APPROVAL_ENQUEUED user={} job={} verdict={} id={}", userId, jobId, verdict, entry.getId());
            return Optional.of(entry);
        } catch (Exception e) {
            record(userId, jobId, null, ApprovalAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("APPROVAL_ENQUEUE error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Gap D — park a PENDING approval request for the NEW "approve this specific filled form"
     * screenshot gate (see {@link ai.careerpilot.execution.browser.GuestApplyAutomationService}),
     * distinct from {@link #enqueue}'s package-level approval — do not conflate the two. A human
     * approves/rejects this row through the SAME existing {@code /api/execution/approve|reject}
     * endpoints as a package approval (same table, same state machine); the only difference is
     * {@code approvalType} and the attached {@code executionId}/{@code screenshotId}. Empty when
     * disabled. Never throws.
     */
    @Transactional
    public Optional<ApprovalQueueEntry> enqueueFormScreenshot(UUID userId, UUID jobId, UUID applicationPackageId,
                                                               UUID executionId, UUID screenshotId, String verdict) {
        if (!enabled) return Optional.empty();
        try {
            ApprovalQueueEntry entry = queue.save(ApprovalQueueEntry.builder()
                    .userId(userId).jobId(jobId)
                    .applicationPackageId(applicationPackageId)
                    .safetyVerdict(verdict)
                    .status(ApprovalQueueEntry.STATUS_PENDING)
                    .approvalType(ApprovalQueueEntry.TYPE_FORM_SCREENSHOT)
                    .executionId(executionId)
                    .screenshotId(screenshotId)
                    .build());
            metrics.recordEnqueued();
            record(userId, jobId, entry.getId(), ApprovalAuditEntry.OUTCOME_ENQUEUED,
                    "type=FORM_SCREENSHOT execution=" + executionId + " screenshot=" + screenshotId);
            log.info("APPROVAL_ENQUEUED_FORM_SCREENSHOT user={} job={} execution={} id={}",
                    userId, jobId, executionId, entry.getId());
            return Optional.of(entry);
        } catch (Exception e) {
            record(userId, jobId, null, ApprovalAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("APPROVAL_ENQUEUE_FORM_SCREENSHOT error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    public List<ApprovalQueueEntry> pending(UUID userId) {
        return queue.findByUserIdAndStatusOrderByRequestedAtDesc(userId, ApprovalQueueEntry.STATUS_PENDING);
    }

    /** Look up an approval entry by id, regardless of owner — used only by internal event-driven workers. */
    public Optional<ApprovalQueueEntry> findById(UUID approvalId) {
        return queue.findById(approvalId);
    }

    /**
     * Human approval. PENDING -> APPROVED + publishes {@link ApprovalGrantedEvent}. 404/403/409 guarded.
     *
     * <p>P7 Action 2 — the PENDING -> APPROVED transition itself is now enforced by an atomic
     * conditional UPDATE ({@link ai.careerpilot.repo.ApprovalQueueRepository#claimDecision}), not by
     * a read-then-write check. The prior implementation read the row, verified {@code PENDING} in
     * memory, then saved — under concurrent callers (a double-click, a duplicate HTTP retry, or a
     * racing {@code approve()}/{@code reject()} pair on the same row) two callers could both pass
     * that check before either committed, and the second one published a second {@link
     * ApprovalGrantedEvent}. {@link ApplicationExecutionService#finalizeGuestApplySubmit
     * finalizeGuestApplySubmit}'s own atomic claim (Action 1) still stops that from reaching a
     * second real browser submission, but this is the approval layer's own invariant, not a
     * downstream backstop for it — exactly one caller may ever observe a winning transition, and
     * every other caller gets the identical 409 a sequential double-approve already produced.
     */
    @Transactional
    public ApprovalQueueEntry approve(UUID approvalId, UUID userId, String decidedBy, String note) {
        requireOwned(approvalId, userId);
        int claimed = queue.claimDecision(approvalId, ApprovalQueueEntry.STATUS_APPROVED, decidedBy, note, Instant.now());
        if (claimed != 1) {
            throw conflict(approvalId);
        }
        ApprovalQueueEntry entry = queue.findById(approvalId)
                .orElseThrow(() -> new NoSuchElementException("approval not found"));
        metrics.recordApproved();
        record(userId, entry.getJobId(), entry.getId(), ApprovalAuditEntry.OUTCOME_APPROVED, note);
        events.publishEvent(new ApprovalGrantedEvent(
                userId, entry.getJobId(), entry.getApplicationPackageId(), entry.getId(), decidedBy));
        log.info("APPROVAL_APPROVED user={} job={} id={} by={}", userId, entry.getJobId(), entry.getId(), decidedBy);
        return entry;
    }

    /**
     * Human rejection. PENDING -> REJECTED, publishes NOTHING. 404/403/409 guarded.
     *
     * <p>P7 Action 2 — same atomic claim as {@link #approve}, so a reject can never race an approve
     * (or another reject) on the same row into a double decision either; only which one lands
     * changes, not the guarantee that exactly one does.
     */
    @Transactional
    public ApprovalQueueEntry reject(UUID approvalId, UUID userId, String decidedBy, String note) {
        requireOwned(approvalId, userId);
        int claimed = queue.claimDecision(approvalId, ApprovalQueueEntry.STATUS_REJECTED, decidedBy, note, Instant.now());
        if (claimed != 1) {
            throw conflict(approvalId);
        }
        ApprovalQueueEntry entry = queue.findById(approvalId)
                .orElseThrow(() -> new NoSuchElementException("approval not found"));
        metrics.recordRejected();
        record(userId, entry.getJobId(), entry.getId(), ApprovalAuditEntry.OUTCOME_REJECTED, note);
        log.info("APPROVAL_REJECTED user={} job={} id={} by={}", userId, entry.getJobId(), entry.getId(), decidedBy);
        return entry;
    }

    /** Existence + ownership only — 404/403. Never inspects status; the atomic claim owns that. */
    private void requireOwned(UUID approvalId, UUID userId) {
        ApprovalQueueEntry entry = queue.findById(approvalId)
                .orElseThrow(() -> new NoSuchElementException("approval not found"));
        if (!entry.getUserId().equals(userId)) {
            throw new SecurityException("approval does not belong to this user");
        }
    }

    /** Built only on the losing path, so the 409 message reports the real post-race status. */
    private IllegalStateException conflict(UUID approvalId) {
        metrics.recordConflict();
        String status = queue.findById(approvalId).map(ApprovalQueueEntry::getStatus).orElse("UNKNOWN");
        return new IllegalStateException("approval already " + status + " — cannot re-decide");
    }

    private void record(UUID userId, UUID jobId, UUID approvalId, String outcome, String reason) {
        audit.save(ApprovalAuditEntry.builder()
                .userId(userId).jobId(jobId).approvalId(approvalId)
                .outcome(outcome).reason(reason)
                .build());
    }
}
