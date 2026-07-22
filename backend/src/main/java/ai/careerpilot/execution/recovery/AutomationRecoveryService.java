package ai.careerpilot.execution.recovery;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import ai.careerpilot.domain.ApplicationRetry;
import ai.careerpilot.execution.browser.BrowserSessionManager;
import ai.careerpilot.execution.retry.RetryDecision;
import ai.careerpilot.execution.retry.RetryPolicyService;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Phase 7.16.3 — the ONE Automation Recovery Center service. Reuses {@link RetryPolicyService} for
 * the actual failure-classification/decision policy (does not duplicate it) — this class is glue:
 * it turns a {@code RetryDecision} into a persisted {@link ApplicationExecution} state change plus
 * timeline/audit trail, and (for {@code BROWSER_FAILURE}) triggers a zombie-gated browser restart.
 *
 * <p>Deliberately has NO dependency on {@code ApplicationExecutionService} — that service depends on
 * this one (to decide what to do about a failure), so the reverse dependency would be a Spring bean
 * cycle. Actually spawning a new attempt row lives in {@code ApplicationExecutionService
 * #retryExecution}, invoked by {@code RecoveryScheduler}; this class only decides/records.
 *
 * <p>Ships dark: {@code application.recovery.enabled=false} makes {@link #attemptRecovery} a no-op
 * (returns {@code Optional.empty()}), so {@code ApplicationExecutionService}'s failure paths are
 * unchanged from pre-7.16.3 behavior until the flag is flipped.
 */
@Service
public class AutomationRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(AutomationRecoveryService.class);

    private final ApplicationExecutionRepository executions;
    private final ApplicationExecutionAuditRepository audit;
    private final RetryPolicyService retryPolicy;
    private final TimelineService timeline;
    private final BrowserSessionManager sessionManager;
    private final RecoveryMetrics metrics;
    private final boolean enabled;
    private final boolean browserRestartEnabled;

    public AutomationRecoveryService(ApplicationExecutionRepository executions,
                                     ApplicationExecutionAuditRepository audit,
                                     RetryPolicyService retryPolicy,
                                     TimelineService timeline,
                                     BrowserSessionManager sessionManager,
                                     RecoveryMetrics metrics,
                                     @Value("${application.recovery.enabled:false}") boolean enabled,
                                     @Value("${browser.recovery.session-restart.enabled:false}") boolean browserRestartEnabled) {
        this.executions = executions;
        this.audit = audit;
        this.retryPolicy = retryPolicy;
        this.timeline = timeline;
        this.sessionManager = sessionManager;
        this.metrics = metrics;
        this.enabled = enabled;
        this.browserRestartEnabled = browserRestartEnabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Called by {@code ApplicationExecutionService} the moment an execution is about to become
     * terminal {@code FAILED} (never for {@code ABORTED} — that means "not applicable," not a
     * transient failure). Classifies + decides via {@link RetryPolicyService}, then:
     * <ul>
     *   <li>{@code RETRY}/{@code RETRY_BACKOFF} — parks {@code exec} at {@code STATUS_RETRY} with
     *       {@code nextRetryAt} set; the caller must NOT also mark it FAILED.</li>
     *   <li>{@code PAUSE} — parks {@code exec} at {@code STATUS_MANUAL_REVIEW}; same caller contract.</li>
     *   <li>{@code STOP} — does not touch {@code exec}; caller proceeds with its normal terminal
     *       {@code FAILED} transition.</li>
     * </ul>
     * Returns {@code Optional.empty()} only when recovery is disabled (flag off) — in every other
     * case a decision is always returned so the caller can branch on {@code shouldRetry()}/action.
     * Never throws.
     */
    @Transactional
    public Optional<RetryDecision> attemptRecovery(ApplicationExecution exec, String failureReason) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordAttempt();
        try {
            int attempt = exec.getAttemptCount() == null ? 1 : exec.getAttemptCount();
            RetryDecision decision = retryPolicy.handleFailure(exec.getId(), exec.getUserId(), exec.getJobId(),
                    attempt, failureReason);

            if (ApplicationRetry.CLASS_BROWSER_FAILURE.equals(decision.failureClass()) && browserRestartEnabled) {
                try {
                    if (sessionManager.restartIfZombie()) {
                        metrics.recordBrowserRestart();
                    }
                } catch (Exception e) {
                    log.warn("AUTOMATION_RECOVERY browser restart check failed execution={}: {}", exec.getId(), e.toString());
                }
            }

            timeline.append(exec.getUserId(), exec.getJobId(), RecoveryTimelineEvents.RECOVERY_STARTED,
                    RecoveryTimelineEvents.SOURCE, null,
                    "failureClass=" + decision.failureClass() + " action=" + decision.action());
            record(exec, "RECOVERY_" + decision.action(), failureReason);

            switch (decision.action()) {
                case ApplicationRetry.ACTION_RETRY, ApplicationRetry.ACTION_RETRY_BACKOFF -> {
                    exec.setExecutionStatus(ApplicationExecution.STATUS_RETRY);
                    exec.setFailureReason(failureReason);
                    exec.setNextRetryAt(Instant.now().plusMillis(decision.backoffMs()));
                    executions.save(exec);
                    metrics.recordRetryScheduled();
                    timeline.append(exec.getUserId(), exec.getJobId(), RecoveryTimelineEvents.RETRY_STARTED,
                            RecoveryTimelineEvents.SOURCE, null, "scheduled, backoffMs=" + decision.backoffMs());
                }
                case ApplicationRetry.ACTION_PAUSE -> {
                    exec.setExecutionStatus(ApplicationExecution.STATUS_MANUAL_REVIEW);
                    exec.setFailureReason(failureReason);
                    executions.save(exec);
                    metrics.recordManualReview();
                    timeline.append(exec.getUserId(), exec.getJobId(), RecoveryTimelineEvents.MANUAL_REVIEW_REQUESTED,
                            RecoveryTimelineEvents.SOURCE, null, failureReason);
                }
                default -> metrics.recordStopped(); // STOP — caller proceeds with normal terminal(FAILED)
            }
            return Optional.of(decision);
        } finally {
            metrics.recordLatency(System.currentTimeMillis() - start);
        }
    }

    /** Called by {@code ApplicationExecutionService#retryExecution} once the new attempt resolves. */
    public void recordRecoveryOutcome(ApplicationExecution previous, ApplicationExecution newAttempt) {
        timeline.append(previous.getUserId(), previous.getJobId(), RecoveryTimelineEvents.CHECKPOINT_RESTORED,
                RecoveryTimelineEvents.SOURCE, null, "checkpoint=" + previous.getCheckpoint());
        if (ApplicationExecution.STATUS_SUBMITTED.equals(newAttempt.getExecutionStatus())) {
            metrics.recordRecoverySuccess();
            timeline.append(newAttempt.getUserId(), newAttempt.getJobId(), RecoveryTimelineEvents.RETRY_SUCCEEDED,
                    RecoveryTimelineEvents.SOURCE, null, "recoveredFrom=" + previous.getId());
            timeline.append(newAttempt.getUserId(), newAttempt.getJobId(), RecoveryTimelineEvents.AUTOMATION_RECOVERED,
                    RecoveryTimelineEvents.SOURCE, null, "attempt=" + newAttempt.getAttemptCount());
            record(newAttempt, "AUTOMATION_RECOVERED", "recovered from execution " + previous.getId());
        } else {
            metrics.recordRecoveryFailure();
            timeline.append(newAttempt.getUserId(), newAttempt.getJobId(), RecoveryTimelineEvents.RETRY_FAILED,
                    RecoveryTimelineEvents.SOURCE, null, "recoveredFrom=" + previous.getId() + " status=" + newAttempt.getExecutionStatus());
        }
    }

    /** Called by {@code ApplicationExecutionService#cancel}. */
    public void recordCancellation(ApplicationExecution exec, String reason) {
        metrics.recordCancellation();
        timeline.append(exec.getUserId(), exec.getJobId(), RecoveryTimelineEvents.EXECUTION_CANCELLED,
                RecoveryTimelineEvents.SOURCE, null, reason);
        record(exec, "EXECUTION_CANCELLED", reason);
    }

    private void record(ApplicationExecution exec, String outcome, String reason) {
        try {
            audit.save(ApplicationExecutionAuditEntry.builder()
                    .userId(exec.getUserId()).jobId(exec.getJobId())
                    .applicationExecutionId(exec.getId())
                    .outcome(outcome).reason(reason)
                    .build());
        } catch (Exception e) {
            log.warn("AUTOMATION_RECOVERY audit write failed execution={}: {}", exec.getId(), e.toString());
        }
    }
}
