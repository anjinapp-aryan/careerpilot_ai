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

    public List<ApprovalQueueEntry> pending(UUID userId) {
        return queue.findByUserIdAndStatusOrderByRequestedAtDesc(userId, ApprovalQueueEntry.STATUS_PENDING);
    }

    /** Human approval. PENDING -> APPROVED + publishes {@link ApprovalGrantedEvent}. 404/403/409 guarded. */
    @Transactional
    public ApprovalQueueEntry approve(UUID approvalId, UUID userId, String decidedBy, String note) {
        ApprovalQueueEntry entry = requirePending(approvalId, userId);
        entry.setStatus(ApprovalQueueEntry.STATUS_APPROVED);
        entry.setDecidedBy(decidedBy);
        entry.setDecisionNote(note);
        entry.setDecidedAt(Instant.now());
        queue.save(entry);
        metrics.recordApproved();
        record(userId, entry.getJobId(), entry.getId(), ApprovalAuditEntry.OUTCOME_APPROVED, note);
        events.publishEvent(new ApprovalGrantedEvent(
                userId, entry.getJobId(), entry.getApplicationPackageId(), entry.getId(), decidedBy));
        log.info("APPROVAL_APPROVED user={} job={} id={} by={}", userId, entry.getJobId(), entry.getId(), decidedBy);
        return entry;
    }

    /** Human rejection. PENDING -> REJECTED, publishes NOTHING. 404/403/409 guarded. */
    @Transactional
    public ApprovalQueueEntry reject(UUID approvalId, UUID userId, String decidedBy, String note) {
        ApprovalQueueEntry entry = requirePending(approvalId, userId);
        entry.setStatus(ApprovalQueueEntry.STATUS_REJECTED);
        entry.setDecidedBy(decidedBy);
        entry.setDecisionNote(note);
        entry.setDecidedAt(Instant.now());
        queue.save(entry);
        metrics.recordRejected();
        record(userId, entry.getJobId(), entry.getId(), ApprovalAuditEntry.OUTCOME_REJECTED, note);
        log.info("APPROVAL_REJECTED user={} job={} id={} by={}", userId, entry.getJobId(), entry.getId(), decidedBy);
        return entry;
    }

    private ApprovalQueueEntry requirePending(UUID approvalId, UUID userId) {
        ApprovalQueueEntry entry = queue.findById(approvalId)
                .orElseThrow(() -> new NoSuchElementException("approval not found"));
        if (!entry.getUserId().equals(userId)) {
            throw new SecurityException("approval does not belong to this user");
        }
        if (!ApprovalQueueEntry.STATUS_PENDING.equals(entry.getStatus())) {
            metrics.recordConflict();
            throw new IllegalStateException("approval already " + entry.getStatus() + " — cannot re-decide");
        }
        return entry;
    }

    private void record(UUID userId, UUID jobId, UUID approvalId, String outcome, String reason) {
        audit.save(ApprovalAuditEntry.builder()
                .userId(userId).jobId(jobId).approvalId(approvalId)
                .outcome(outcome).reason(reason)
                .build());
    }
}
