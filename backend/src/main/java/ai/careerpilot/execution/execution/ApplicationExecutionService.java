package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.browser.BrowserAutomationProvider;
import ai.careerpilot.execution.browser.GuestApplyAutomationService;
import ai.careerpilot.execution.recovery.AutomationRecoveryService;
import ai.careerpilot.execution.recovery.ExecutionCheckpoint;
import ai.careerpilot.domain.ApplicationRetry;
import ai.careerpilot.execution.retry.RetryDecision;
import ai.careerpilot.execution.verification.SubmissionVerificationService;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2E.1 / Gap D — the Application Execution Engine's state machine. HIGH RISK, ships DARK
 * (both {@code application.execution.enabled} and {@code browser.automation.enabled} default false).
 *
 * <p>{@link #execute} drives one attempt through
 * {@code QUEUED -> VALIDATING -> EXECUTING -> (SUBMITTED | AWAITING_APPROVAL | ABORTED | FAILED)}.
 *
 * <p><b>Gap D adds a real path to SUBMITTED</b>, but ONLY for a resolved {@link ATSConnector} that
 * is both {@code isConfigured()} AND guest-apply-eligible ({@link
 * ai.careerpilot.execution.ats.GuestApplyEligibility} — hardcoded, e.g. Greenhouse/Lever; never
 * LinkedIn or any login-required connector). For that narrow case, {@link
 * GuestApplyAutomationService#attemptFill} navigates, aborts immediately on any CAPTCHA/login-wall
 * marker, fills only real (never fabricated) applicant fields, screenshots the filled form, and
 * parks a NEW mandatory human "approve this specific filled form" request — the execution moves to
 * the non-terminal {@link ApplicationExecution#STATUS_AWAITING_APPROVAL}, not straight to SUBMITTED.
 * Only {@link #finalizeGuestApplySubmit}, invoked by {@code FormApprovalExecutionWorker} after that
 * human decision, performs the real submit click and reaches terminal SUBMITTED.
 *
 * <p>Every other case — any other connector, or the generic {@link BrowserAutomationProvider} path
 * — is UNCHANGED from the pre-Gap-D build: still resolves to terminal {@code ABORTED} ("... not
 * enabled in this build"). Zero regression for the majority of traffic.
 *
 * <p>Append-only + fully audited; never throws out of {@link #execute} (failures become a terminal
 * {@code FAILED} row + ERROR audit entry).
 */
@Service
public class ApplicationExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExecutionService.class);

    private final ApplicationExecutionRepository executions;
    private final ApplicationExecutionAuditRepository audit;
    private final ApplicationPackageRepository packages;
    private final JobRepository jobs;
    private final ATSConnectorRegistry connectors;
    private final BrowserAutomationProvider browser;
    private final GuestApplyAutomationService guestApply;
    private final SubmissionVerificationService verification;
    private final AutomationRecoveryService recovery;
    private final ApplicationExecutionMetrics metrics;
    private final boolean enabled;

    public ApplicationExecutionService(ApplicationExecutionRepository executions,
                                       ApplicationExecutionAuditRepository audit,
                                       ApplicationPackageRepository packages,
                                       JobRepository jobs,
                                       ATSConnectorRegistry connectors,
                                       BrowserAutomationProvider browser,
                                       GuestApplyAutomationService guestApply,
                                       SubmissionVerificationService verification,
                                       AutomationRecoveryService recovery,
                                       ApplicationExecutionMetrics metrics,
                                       @Value("${application.execution.enabled:false}") boolean enabled) {
        this.executions = executions;
        this.audit = audit;
        this.packages = packages;
        this.jobs = jobs;
        this.connectors = connectors;
        this.browser = browser;
        this.guestApply = guestApply;
        this.verification = verification;
        this.recovery = recovery;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Run one execution attempt for an approved application package. Empty when disabled/missing.
     * Never throws. Resolves to {@code SUBMITTED} only via the (rare, gated) Gap D guest-apply path
     * described in the class javadoc — every other case still resolves to {@code ABORTED}.
     */
    @Transactional
    public Optional<ApplicationExecution> execute(UUID userId, UUID jobId, UUID applicationPackageId) {
        if (!enabled) return Optional.empty();
        long start = System.currentTimeMillis();
        metrics.recordRequest();

        // Resolve inputs defensively — a repo failure here must not escape (this may run on an
        // executor thread whose uncaught exceptions are otherwise unhandled).
        ApplicationPackage pkg;
        Job job;
        try {
            pkg = applicationPackageId != null
                    ? packages.findById(applicationPackageId).orElse(null)
                    : packages.findByUserIdAndJobId(userId, jobId).orElse(null);
            job = jobs.findById(jobId).orElse(null);
        } catch (Exception e) {
            metrics.recordFailure();
            record(userId, jobId, null, ApplicationExecutionAuditEntry.OUTCOME_ERROR, "input resolution error: " + e);
            log.warn("APP_EXECUTION input error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.empty();
        }
        if (pkg == null || job == null) {
            metrics.recordFailure();
            record(userId, jobId, null, ApplicationExecutionAuditEntry.OUTCOME_ERROR, "missing package or job");
            return Optional.empty();
        }

        ApplicationExecution exec = executions.save(ApplicationExecution.builder()
                .userId(userId).jobId(jobId)
                .applicationPackageId(pkg.getId())
                .applicationId(pkg.getApplicationId())
                .executionStatus(ApplicationExecution.STATUS_QUEUED)
                .executionType(ApplicationExecution.TYPE_MANUAL)
                .attemptCount(1)
                .checkpoint(ExecutionCheckpoint.QUEUED)
                .build());
        record(userId, jobId, exec.getId(), ApplicationExecution.STATUS_QUEUED, "execution queued");

        try {
            // VALIDATING — the package must be fully assembled to even consider execution.
            transition(exec, ApplicationExecution.STATUS_VALIDATING, "validating package completeness");
            if (!ApplicationPackage.STATUS_ASSEMBLED.equals(pkg.getStatus())) {
                return terminal(exec, ApplicationExecution.STATUS_ABORTED,
                        "application package not ASSEMBLED (status=" + pkg.getStatus() + ")", start);
            }

            // EXECUTING — resolve the execution backend.
            transition(exec, ApplicationExecution.STATUS_EXECUTING, "resolving execution backend");
            ATSConnector connector = connectors.detect(job);
            if (connector != null && connector.isConfigured()) {
                exec.setExecutionType(ApplicationExecution.TYPE_ATS_CONNECTOR);
                exec.setProvider(connector.name());

                if (guestApply.isEligible(connector)) {
                    return runGuestApplyFill(exec, job, connector, start);
                }
                // Any other (login-required) connector: unchanged pre-Gap-D behavior.
                return terminal(exec, ApplicationExecution.STATUS_ABORTED,
                        "ATS connector present but submission not enabled in this build", start);
            }
            if (browser.isConfigured()) {
                exec.setExecutionType(ApplicationExecution.TYPE_BROWSER);
                exec.setProvider(browser.name());
                return terminal(exec, ApplicationExecution.STATUS_ABORTED,
                        "browser automation present but submission not enabled in this build", start);
            }

            return terminal(exec, ApplicationExecution.STATUS_ABORTED,
                    "no execution backend configured (browser + ATS connectors disabled)", start);
        } catch (Exception e) {
            exec.setExecutionStatus(ApplicationExecution.STATUS_FAILED);
            exec.setFailureReason(e.toString());
            exec.setCompletedAt(Instant.now());
            executions.save(exec);
            metrics.recordFailure();
            metrics.recordLatency(System.currentTimeMillis() - start);
            record(userId, jobId, exec.getId(), ApplicationExecutionAuditEntry.OUTCOME_ERROR, e.toString());
            log.warn("APP_EXECUTION error user={} job={}: {}", userId, jobId, e.toString());
            return Optional.of(exec);
        }
    }

    private Optional<ApplicationExecution> runGuestApplyFill(ApplicationExecution exec, Job job,
                                                              ATSConnector connector, long start) {
        GuestApplyAutomationService.AttemptOutcome outcome = guestApply.attemptFill(exec, job, connector);
        return switch (outcome.kind()) {
            case AWAITING_APPROVAL -> awaitingApproval(exec, start);
            case ABORTED -> terminal(exec, ApplicationExecution.STATUS_ABORTED, outcome.reason(), start);
            case SUBMITTED -> terminal(exec, ApplicationExecution.STATUS_SUBMITTED, null, start);
            case ERROR -> terminal(exec, ApplicationExecution.STATUS_FAILED, outcome.reason(), start);
        };
    }

    /**
     * Gap D — invoked ONLY by {@code FormApprovalExecutionWorker} after a human approves the
     * FORM_SCREENSHOT entry parked by {@link #runGuestApplyFill}. Double-guarded: refuses to act
     * unless the execution is genuinely still {@code AWAITING_APPROVAL} (so a duplicate/rejected
     * event can never re-fire a submit click). Never throws.
     */
    @Transactional
    public void finalizeGuestApplySubmit(UUID executionId) {
        long start = System.currentTimeMillis();
        ApplicationExecution exec = executions.findById(executionId).orElse(null);
        if (exec == null) {
            log.warn("APP_EXECUTION finalizeGuestApplySubmit: execution {} not found", executionId);
            return;
        }
        if (!ApplicationExecution.STATUS_AWAITING_APPROVAL.equals(exec.getExecutionStatus())) {
            log.warn("APP_EXECUTION finalizeGuestApplySubmit refused: execution {} not AWAITING_APPROVAL (status={})",
                    executionId, exec.getExecutionStatus());
            return;
        }
        Job job = jobs.findById(exec.getJobId()).orElse(null);
        if (job == null) {
            terminal(exec, ApplicationExecution.STATUS_FAILED, "job no longer exists", start);
            return;
        }
        ATSConnector connector = connectors.detect(job);
        if (connector == null || !guestApply.isEligible(connector)) {
            terminal(exec, ApplicationExecution.STATUS_FAILED,
                    "resolved connector no longer guest-apply eligible at finalize time", start);
            return;
        }
        GuestApplyAutomationService.AttemptOutcome outcome = guestApply.finalizeSubmit(exec, job, connector);
        switch (outcome.kind()) {
            case SUBMITTED -> {
                // Phase 7.16.1 — verify BEFORE reaching the terminal SUBMITTED state, so the
                // execution's own evidence columns (confirmationNumber/verificationStatus/...)
                // are already populated by the time any caller (e.g. the submission pipeline)
                // reads this row back. Never blocks/fails the SUBMITTED outcome itself — the
                // click already happened; verification only records what we can prove about it.
                exec.setCheckpoint(ExecutionCheckpoint.SUBMIT_CLICKED);
                try {
                    verification.verify(exec, connector, outcome.confirmationReference());
                } catch (Exception e) {
                    log.warn("APP_EXECUTION verification call failed execution={}: {}", exec.getId(), e.toString());
                }
                exec.setCheckpoint(ExecutionCheckpoint.VERIFICATION_COMPLETE);
                terminal(exec, ApplicationExecution.STATUS_SUBMITTED, null, start);
            }
            case ABORTED -> terminal(exec, ApplicationExecution.STATUS_ABORTED, outcome.reason(), start);
            default -> terminal(exec, ApplicationExecution.STATUS_FAILED, outcome.reason(), start);
        }
    }

    public Optional<ApplicationExecution> status(UUID executionId, UUID userId) {
        return executions.findByIdAndUserId(executionId, userId);
    }

    /**
     * Phase 7.16.3 — spawns a NEW attempt for an execution parked at {@code STATUS_RETRY} or
     * {@code STATUS_MANUAL_REVIEW} (the latter only via an explicit human "retry now" action, never
     * automatically). Called by {@code RecoveryScheduler} for due RETRY rows, and by {@code
     * ExecutionController}'s manual retry-now endpoint for either status. Reuses {@link #execute}
     * verbatim — a retry always re-runs from scratch (new browser context), it never resumes a
     * literal browser session mid-page; only the {@code checkpoint} value is carried forward for
     * observability. The old row is marked {@code STATUS_RETRIED} (terminal for that row; the chain
     * continues on the new row via {@code retryOfExecutionId}). Never throws.
     */
    @Transactional
    public Optional<ApplicationExecution> retryExecution(UUID executionId) {
        ApplicationExecution previous = executions.findById(executionId).orElse(null);
        if (previous == null) {
            return Optional.empty();
        }
        if (!ApplicationExecution.STATUS_RETRY.equals(previous.getExecutionStatus())
                && !ApplicationExecution.STATUS_MANUAL_REVIEW.equals(previous.getExecutionStatus())) {
            log.warn("APP_EXECUTION retryExecution refused: execution {} not RETRY/MANUAL_REVIEW (status={})",
                    executionId, previous.getExecutionStatus());
            return Optional.empty();
        }
        Optional<ApplicationExecution> result = execute(previous.getUserId(), previous.getJobId(),
                previous.getApplicationPackageId());
        result.ifPresent(newAttempt -> {
            newAttempt.setRetryOfExecutionId(previous.getId());
            newAttempt.setAttemptCount((previous.getAttemptCount() == null ? 1 : previous.getAttemptCount()) + 1);
            newAttempt.setCheckpoint(previous.getCheckpoint());
            executions.save(newAttempt);
            try {
                recovery.recordRecoveryOutcome(previous, newAttempt);
            } catch (Exception e) {
                log.warn("APP_EXECUTION recovery outcome recording failed execution={}: {}", newAttempt.getId(), e.toString());
            }
        });
        previous.setExecutionStatus(ApplicationExecution.STATUS_RETRIED);
        previous.setCompletedAt(Instant.now());
        executions.save(previous);
        return result;
    }

    /**
     * Phase 7.16.3 — human-initiated cancellation of a non-terminal execution (Recovery Center
     * "Cancel Execution" action). Refuses on an already-terminal row (SUBMITTED/ABORTED/FAILED/
     * RETRIED) so a stale cancel click can never corrupt a finished outcome. Never throws.
     */
    @Transactional
    public boolean cancel(UUID executionId, UUID userId, String reason) {
        ApplicationExecution exec = executions.findByIdAndUserId(executionId, userId).orElse(null);
        if (exec == null || isTerminal(exec.getExecutionStatus())) {
            return false;
        }
        exec.setExecutionStatus(ApplicationExecution.STATUS_ABORTED);
        exec.setFailureReason("cancelled by user: " + reason);
        exec.setCompletedAt(Instant.now());
        executions.save(exec);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), ApplicationExecution.STATUS_ABORTED, "cancelled: " + reason);
        try {
            recovery.recordCancellation(exec, reason);
        } catch (Exception e) {
            log.warn("APP_EXECUTION cancellation recording failed execution={}: {}", exec.getId(), e.toString());
        }
        return true;
    }

    /**
     * Phase 7.16.4 — user action "Request Manual Review": a human-initiated escalation of a
     * still-running execution straight to {@code STATUS_MANUAL_REVIEW}, distinct from the automatic
     * PAUSE decision {@link AutomationRecoveryService} makes on a CAPTCHA/confirmation-missing
     * failure. Refuses on an already-terminal row. Never throws.
     */
    @Transactional
    public boolean requestManualReview(UUID executionId, UUID userId, String reason) {
        ApplicationExecution exec = executions.findByIdAndUserId(executionId, userId).orElse(null);
        if (exec == null || isTerminal(exec.getExecutionStatus())
                || ApplicationExecution.STATUS_MANUAL_REVIEW.equals(exec.getExecutionStatus())) {
            return false;
        }
        exec.setExecutionStatus(ApplicationExecution.STATUS_MANUAL_REVIEW);
        exec.setFailureReason(reason);
        executions.save(exec);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), ApplicationExecution.STATUS_MANUAL_REVIEW,
                "manual review requested: " + reason);
        return true;
    }

    private static boolean isTerminal(String status) {
        return ApplicationExecution.STATUS_SUBMITTED.equals(status)
                || ApplicationExecution.STATUS_ABORTED.equals(status)
                || ApplicationExecution.STATUS_FAILED.equals(status)
                || ApplicationExecution.STATUS_RETRIED.equals(status);
    }

    // ── state-machine helpers ──

    private void transition(ApplicationExecution exec, String status, String reason) {
        if (ApplicationExecution.STATUS_EXECUTING.equals(status) && exec.getStartedAt() == null) {
            exec.setStartedAt(Instant.now());
        }
        exec.setExecutionStatus(status);
        exec.setCheckpoint(switch (status) {
            case ApplicationExecution.STATUS_VALIDATING -> ExecutionCheckpoint.VALIDATING;
            case ApplicationExecution.STATUS_EXECUTING -> ExecutionCheckpoint.JOB_LOADED;
            default -> exec.getCheckpoint();
        });
        executions.save(exec);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), status, reason);
    }

    /** Gap D — parks the execution at the new non-terminal AWAITING_APPROVAL status (not a terminal()). */
    private Optional<ApplicationExecution> awaitingApproval(ApplicationExecution exec, long start) {
        exec.setExecutionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL);
        exec.setCheckpoint(ExecutionCheckpoint.FORM_FILLED);
        executions.save(exec);
        metrics.recordAwaitingApproval();
        metrics.recordLatency(System.currentTimeMillis() - start);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), ApplicationExecution.STATUS_AWAITING_APPROVAL,
                "guest-apply form filled — awaiting human screenshot approval");
        log.info("APP_EXECUTION user={} job={} type={} status=AWAITING_APPROVAL",
                exec.getUserId(), exec.getJobId(), exec.getExecutionType());
        return Optional.of(exec);
    }

    private Optional<ApplicationExecution> terminal(ApplicationExecution exec, String status,
                                                    String reason, long start) {
        // Phase 7.16.3 — a transient FAILED (never ABORTED, which means "not applicable") is first
        // offered to the Automation Recovery Center. When recovery is enabled and decides to
        // RETRY/RETRY_BACKOFF/PAUSE, it has already mutated + saved `exec` itself (STATUS_RETRY or
        // STATUS_MANUAL_REVIEW) — this method must NOT then overwrite that with STATUS_FAILED. A
        // STOP decision (or recovery disabled) falls through to the unchanged pre-7.16.3 behavior.
        if (ApplicationExecution.STATUS_FAILED.equals(status)) {
            Optional<RetryDecision> decision = recovery.attemptRecovery(exec, reason);
            if (decision.isPresent()) {
                String action = decision.get().action();
                if (decision.get().shouldRetry() || ApplicationRetry.ACTION_PAUSE.equals(action)) {
                    metrics.recordLatency(System.currentTimeMillis() - start);
                    return Optional.of(exec);
                }
            }
        }
        exec.setExecutionStatus(status);
        exec.setFailureReason(ApplicationExecution.STATUS_SUBMITTED.equals(status) ? null : reason);
        exec.setCompletedAt(Instant.now());
        executions.save(exec);
        switch (status) {
            case ApplicationExecution.STATUS_SUBMITTED -> metrics.recordSubmitted();
            case ApplicationExecution.STATUS_ABORTED -> metrics.recordAborted();
            default -> metrics.recordFailure();
        }
        metrics.recordLatency(System.currentTimeMillis() - start);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), status, reason);
        log.info("APP_EXECUTION user={} job={} type={} status={} reason={}",
                exec.getUserId(), exec.getJobId(), exec.getExecutionType(), status, reason);
        return Optional.of(exec);
    }

    private void record(UUID userId, UUID jobId, UUID executionId, String outcome, String reason) {
        audit.save(ApplicationExecutionAuditEntry.builder()
                .userId(userId).jobId(jobId)
                .applicationExecutionId(executionId)
                .outcome(outcome).reason(reason)
                .build());
    }
}
