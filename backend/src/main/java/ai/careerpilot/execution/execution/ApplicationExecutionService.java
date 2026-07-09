package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.browser.BrowserAutomationProvider;
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
 * Phase 2E.1 — the Application Execution Engine's state machine. HIGH RISK, ships DARK.
 *
 * <p>{@link #execute} drives one attempt through
 * {@code QUEUED -> VALIDATING -> EXECUTING -> (SUBMITTED | ABORTED | FAILED)}. The terminal
 * {@code SUBMITTED} state is <b>unreachable in the 2E build</b>: the actual submission is delegated
 * to an execution backend (an {@link ATSConnector} if one {@code detect()}s + is configured, else
 * the {@link BrowserAutomationProvider}), and in this phase every connector is unconfigured and the
 * browser provider is a throwing stub — so an execution that starts can only resolve to
 * {@code ABORTED "no execution backend configured"}. Nothing is ever submitted.
 *
 * <p>Append-only + fully audited; never throws out of {@link #execute} (failures become a terminal
 * {@code FAILED} row + ERROR audit entry). Flag-gated dark by {@code application.execution.enabled}.
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
    private final ApplicationExecutionMetrics metrics;
    private final boolean enabled;

    public ApplicationExecutionService(ApplicationExecutionRepository executions,
                                       ApplicationExecutionAuditRepository audit,
                                       ApplicationPackageRepository packages,
                                       JobRepository jobs,
                                       ATSConnectorRegistry connectors,
                                       BrowserAutomationProvider browser,
                                       ApplicationExecutionMetrics metrics,
                                       @Value("${application.execution.enabled:false}") boolean enabled) {
        this.executions = executions;
        this.audit = audit;
        this.packages = packages;
        this.jobs = jobs;
        this.connectors = connectors;
        this.browser = browser;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Run one execution attempt for an approved application package. Empty when disabled/missing.
     * Never throws. In the 2E build this always terminates in {@code ABORTED} (no execution backend).
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
                .build());
        record(userId, jobId, exec.getId(), ApplicationExecution.STATUS_QUEUED, "execution queued");

        try {
            // VALIDATING — the package must be fully assembled to even consider execution.
            transition(exec, ApplicationExecution.STATUS_VALIDATING, "validating package completeness");
            if (!ApplicationPackage.STATUS_ASSEMBLED.equals(pkg.getStatus())) {
                return terminal(exec, ApplicationExecution.STATUS_ABORTED,
                        "application package not ASSEMBLED (status=" + pkg.getStatus() + ")", start);
            }

            // EXECUTING — resolve the execution backend. In the 2E build none is available.
            transition(exec, ApplicationExecution.STATUS_EXECUTING, "resolving execution backend");
            ATSConnector connector = connectors.detect(job);
            if (connector != null && connector.isConfigured()) {
                exec.setExecutionType(ApplicationExecution.TYPE_ATS_CONNECTOR);
                exec.setProvider(connector.name());
                // No connector is configured in the 2E build; guarded here for the future path.
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

    public Optional<ApplicationExecution> status(UUID executionId, UUID userId) {
        return executions.findByIdAndUserId(executionId, userId);
    }

    // ── state-machine helpers ──

    private void transition(ApplicationExecution exec, String status, String reason) {
        if (ApplicationExecution.STATUS_EXECUTING.equals(status) && exec.getStartedAt() == null) {
            exec.setStartedAt(Instant.now());
        }
        exec.setExecutionStatus(status);
        executions.save(exec);
        record(exec.getUserId(), exec.getJobId(), exec.getId(), status, reason);
    }

    private Optional<ApplicationExecution> terminal(ApplicationExecution exec, String status,
                                                    String reason, long start) {
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
