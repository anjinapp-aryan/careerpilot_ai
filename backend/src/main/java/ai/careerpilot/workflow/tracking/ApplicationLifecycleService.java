package ai.careerpilot.workflow.tracking;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.ApplicationLifecycleAudit;
import ai.careerpilot.domain.ApplicationStatusHistory;
import ai.careerpilot.repo.ApplicationLifecycleAuditRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationStatusHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.1 — the application lifecycle state machine. Owns the {@code (user, job)} lifecycle row,
 * validates every status change through {@link ApplicationStatusMachine}, and records each accepted
 * change append-only in status-history + audit. Flag-gated dark by {@code workflow.tracking.enabled};
 * an invalid transition is refused (logged, counted) without throwing, and any persistence error is
 * swallowed — this service NEVER throws, so a worker can call it safely on an async thread.
 */
@Service
public class ApplicationLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationLifecycleService.class);

    private final ApplicationLifecycleRepository lifecycles;
    private final ApplicationStatusHistoryRepository history;
    private final ApplicationLifecycleAuditRepository audit;
    private final WorkflowTrackingMetrics metrics;
    private final boolean enabled;

    public ApplicationLifecycleService(ApplicationLifecycleRepository lifecycles,
                                       ApplicationStatusHistoryRepository history,
                                       ApplicationLifecycleAuditRepository audit,
                                       WorkflowTrackingMetrics metrics,
                                       @Value("${workflow.tracking.enabled:false}") boolean enabled) {
        this.lifecycles = lifecycles;
        this.history = history;
        this.audit = audit;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Idempotently open (or return the existing) lifecycle row for a (user, job), at SUBMITTED. Empty
     * when disabled/failed. Never throws.
     */
    @Transactional
    public Optional<ApplicationLifecycle> createOrGet(UUID userId, UUID jobId, UUID applicationId,
                                                      String company, String country, String source) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            Optional<ApplicationLifecycle> existing = lifecycles.findByUserIdAndJobId(userId, jobId);
            if (existing.isPresent()) return existing;
            ApplicationLifecycle row = lifecycles.save(ApplicationLifecycle.builder()
                    .userId(userId).jobId(jobId).applicationId(applicationId)
                    .company(company).country(country).source(source)
                    .applicationDate(Instant.now())
                    .previousStatus(ApplicationLifecycle.STATUS_DRAFT)
                    .currentStatus(ApplicationLifecycle.STATUS_SUBMITTED)
                    .build());
            appendHistory(row.getId(), ApplicationLifecycle.STATUS_DRAFT, ApplicationLifecycle.STATUS_SUBMITTED, "application created");
            appendAudit(row.getId(), "CREATE", source, "opened at SUBMITTED");
            metrics.recordCreate();
            log.info("APP_LIFECYCLE created user={} job={}", userId, jobId);
            return Optional.of(row);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_LIFECYCLE create failed user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Validate and apply a status transition. Returns the updated row, or empty when disabled, when the
     * row is missing, or when the transition is illegal (refused, not an exception). Never throws.
     */
    @Transactional
    public Optional<ApplicationLifecycle> transition(UUID userId, UUID jobId, String newStatus, String note) {
        if (!enabled) return Optional.empty();
        metrics.recordRequest();
        try {
            Optional<ApplicationLifecycle> found = lifecycles.findByUserIdAndJobId(userId, jobId);
            if (found.isEmpty()) return Optional.empty();
            ApplicationLifecycle row = found.get();
            String from = row.getCurrentStatus();
            if (!ApplicationStatusMachine.canTransition(from, newStatus)) {
                metrics.recordRejectedTransition();
                log.info("APP_LIFECYCLE refused illegal transition {} -> {} user={} job={}", from, newStatus, userId, jobId);
                appendAudit(row.getId(), "REFUSED", null, from + " -> " + newStatus);
                return Optional.empty();
            }
            row.setPreviousStatus(from);
            row.setCurrentStatus(newStatus);
            lifecycles.save(row);
            appendHistory(row.getId(), from, newStatus, note);
            appendAudit(row.getId(), "TRANSITION", null, from + " -> " + newStatus);
            metrics.recordTransition();
            log.info("APP_LIFECYCLE {} -> {} user={} job={}", from, newStatus, userId, jobId);
            return Optional.of(row);
        } catch (Exception e) {
            metrics.recordFailure();
            log.warn("APP_LIFECYCLE transition failed user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
    }

    public Optional<ApplicationLifecycle> find(UUID userId, UUID jobId) {
        return lifecycles.findByUserIdAndJobId(userId, jobId);
    }

    public List<ApplicationStatusHistory> statusHistory(UUID lifecycleId) {
        return history.findByLifecycleIdOrderByChangedAtDesc(lifecycleId);
    }

    private void appendHistory(UUID lifecycleId, String from, String to, String note) {
        history.save(ApplicationStatusHistory.builder()
                .lifecycleId(lifecycleId).fromStatus(from).toStatus(to).note(note).build());
    }

    private void appendAudit(UUID lifecycleId, String action, String actor, String detail) {
        audit.save(ApplicationLifecycleAudit.builder()
                .lifecycleId(lifecycleId).action(action).actor(actor).detail(detail).build());
    }
}
