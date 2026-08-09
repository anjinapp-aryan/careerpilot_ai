package ai.careerpilot.execution.operations;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.ats.GuestApplyEligibility;
import ai.careerpilot.execution.execution.ApplicationExecutionMetrics;
import ai.careerpilot.execution.recovery.RecoveryMetrics;
import ai.careerpilot.execution.verification.VerificationMetrics;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationRetryRepository;
import ai.careerpilot.repo.ApprovalQueueRepository;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7.16.4 — the Application Operations Center's aggregation layer. Every number here is a
 * derivation of data that already exists (execution rows, {@code ApplicationRetry} rows, audit
 * entries, timeline entries, and the in-memory metrics beans from 7.16.1-7.16.3) — this class adds
 * NO new persistence and NO new counters; it only joins/derives. Read-only throughout — no method
 * here mutates any row.
 *
 * <p>Two honesty notes, stated once here rather than scattered across methods:
 * <ul>
 *   <li>"Verification pending" is always 0 — verification runs synchronously inside {@code
 *       ApplicationExecutionService#finalizeGuestApplySubmit}, so a readable SUBMITTED row already
 *       has its verification outcome. There is no persisted "verifying" window today.</li>
 *   <li>{@link #detail} returns the JOB's whole timeline, not one filtered to a single execution
 *       attempt — {@code ApplicationTimeline} has no {@code executionId} column (it's keyed by
 *       user+job only), so a job with multiple retry attempts shows every attempt's events
 *       interleaved. This is a real limitation of the existing schema, not something this phase
 *       papers over.</li>
 * </ul>
 */
@Service
public class OperationsService {

    private final ApplicationExecutionRepository executions;
    private final ApplicationExecutionAuditRepository audit;
    private final ApplicationRetryRepository retries;
    private final ExecutionScreenshotRepository screenshots;
    private final ApprovalQueueRepository approvals;
    private final ATSConnectorRegistry atsRegistry;
    private final TimelineService timelineService;
    private final ApplicationExecutionMetrics executionMetrics;
    private final VerificationMetrics verificationMetrics;
    private final RecoveryMetrics recoveryMetrics;

    public OperationsService(ApplicationExecutionRepository executions,
                             ApplicationExecutionAuditRepository audit,
                             ApplicationRetryRepository retries,
                             ExecutionScreenshotRepository screenshots,
                             ApprovalQueueRepository approvals,
                             ATSConnectorRegistry atsRegistry,
                             TimelineService timelineService,
                             ApplicationExecutionMetrics executionMetrics,
                             VerificationMetrics verificationMetrics,
                             RecoveryMetrics recoveryMetrics) {
        this.executions = executions;
        this.audit = audit;
        this.retries = retries;
        this.screenshots = screenshots;
        this.approvals = approvals;
        this.atsRegistry = atsRegistry;
        this.timelineService = timelineService;
        this.executionMetrics = executionMetrics;
        this.verificationMetrics = verificationMetrics;
        this.recoveryMetrics = recoveryMetrics;
    }

    /** Global Operations Dashboard — Objective 1. */
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        long queued = executions.countByExecutionStatus(ApplicationExecution.STATUS_QUEUED);
        long validating = executions.countByExecutionStatus(ApplicationExecution.STATUS_VALIDATING);
        long executing = executions.countByExecutionStatus(ApplicationExecution.STATUS_EXECUTING);
        long submitted = executions.countByExecutionStatus(ApplicationExecution.STATUS_SUBMITTED);
        long verified = executions.countByExecutionStatusAndVerificationStatus(ApplicationExecution.STATUS_SUBMITTED, "VERIFIED");
        long verificationNull = executions.countByExecutionStatusAndVerificationStatusIsNull(ApplicationExecution.STATUS_SUBMITTED);
        long completed = verified + verificationNull;
        long verificationFailed = submitted - completed;
        long aborted = executions.countByExecutionStatus(ApplicationExecution.STATUS_ABORTED);
        long cancelled = executions.countByExecutionStatusAndFailureReasonStartingWith(
                ApplicationExecution.STATUS_ABORTED, "cancelled by user:");
        long failed = executions.countByExecutionStatus(ApplicationExecution.STATUS_FAILED);
        long recovered = executions.countByExecutionStatusAndRetryOfExecutionIdIsNotNull(ApplicationExecution.STATUS_SUBMITTED);

        out.put("running", queued + validating + executing);
        out.put("queued", queued);
        out.put("waitingApproval", executions.countByExecutionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL));
        out.put("retrying", executions.countByExecutionStatus(ApplicationExecution.STATUS_RETRY));
        out.put("recovered", recovered);
        out.put("paused", executions.countByExecutionStatus(ApplicationExecution.STATUS_MANUAL_REVIEW));
        out.put("cancelled", cancelled);
        out.put("verificationPending", 0L); // see class javadoc — verification is synchronous today
        out.put("verificationFailed", Math.max(verificationFailed, 0));
        out.put("completed", completed);
        out.put("failed", failed);
        out.put("aborted", aborted);

        Map<String, Object> execSnapshot = executionMetrics.snapshot();
        Map<String, Object> verSnapshot = verificationMetrics.snapshot();
        Map<String, Object> recSnapshot = recoveryMetrics.snapshot();
        out.put("avgSubmissionTimeMs", execSnapshot.get("applicationExecutionAvgLatencyMs"));
        out.put("avgVerificationTimeMs", verSnapshot.get("avgVerificationLatencyMs"));
        out.put("avgRecoveryTimeMs", recSnapshot.get("avgRecoveryLatencyMs"));

        long terminalTotal = submitted + aborted + failed;
        out.put("automationSuccessRate", terminalTotal == 0 ? 0.0 : (submitted * 100.0 / terminalTotal));
        out.put("recoverySuccessRate", recSnapshot.get("recoverySuccessRate"));
        out.put("verificationSuccessRate", verSnapshot.get("verificationSuccessRate"));
        return out;
    }

    /** Fleet View — Objective 2. Bounded to the most recent 1000 provider-attributed executions. */
    public Map<String, Object> fleet() {
        List<ApplicationExecution> recent = executions.findTop1000ByProviderIsNotNullOrderByCreatedAtDesc();
        Map<String, List<ApplicationExecution>> byProvider = recent.stream()
                .collect(Collectors.groupingBy(ApplicationExecution::getProvider));

        List<Map<String, Object>> providers = new ArrayList<>();
        for (ATSConnector connector : atsRegistry.all()) {
            List<ApplicationExecution> rows = byProvider.getOrDefault(connector.name(), List.of());
            providers.add(providerStats(connector.name(), connector.isConfigured(), rows));
        }
        // Rows attributed to a provider name with no registered connector (renamed/removed connector) —
        // surfaced as "Unknown ATS" per the spec rather than silently dropped.
        List<String> known = atsRegistry.all().stream().map(ATSConnector::name).toList();
        List<ApplicationExecution> unknownRows = recent.stream()
                .filter(e -> !known.contains(e.getProvider())).toList();
        if (!unknownRows.isEmpty()) {
            providers.add(providerStats("unknown", false, unknownRows));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("providers", providers);
        out.put("windowSize", recent.size());
        return out;
    }

    private Map<String, Object> providerStats(String name, boolean configured, List<ApplicationExecution> rows) {
        long total = rows.size();
        long running = rows.stream().filter(e -> isRunning(e.getExecutionStatus())).count();
        long failures = rows.stream().filter(e -> ApplicationExecution.STATUS_FAILED.equals(e.getExecutionStatus())).count();
        long recoveries = rows.stream().filter(e -> e.getRetryOfExecutionId() != null).count();
        long submitted = rows.stream().filter(e -> ApplicationExecution.STATUS_SUBMITTED.equals(e.getExecutionStatus())).count();

        Optional<Instant> lastFailure = rows.stream()
                .filter(e -> ApplicationExecution.STATUS_FAILED.equals(e.getExecutionStatus()))
                .map(ApplicationExecution::getCreatedAt).max(Comparator.naturalOrder());
        Optional<Instant> lastSuccess = rows.stream()
                .filter(e -> ApplicationExecution.STATUS_SUBMITTED.equals(e.getExecutionStatus()))
                .map(ApplicationExecution::getCreatedAt).max(Comparator.naturalOrder());

        List<Long> submissionDurations = rows.stream()
                .filter(e -> ApplicationExecution.STATUS_SUBMITTED.equals(e.getExecutionStatus())
                        && e.getStartedAt() != null && e.getCompletedAt() != null)
                .map(e -> Duration.between(e.getStartedAt(), e.getCompletedAt()).toMillis())
                .toList();
        Double avgSubmissionMs = submissionDurations.isEmpty() ? null
                : submissionDurations.stream().mapToLong(Long::longValue).average().orElse(0.0);

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("provider", name);
        p.put("configured", configured);
        p.put("guestApplyEligible", GuestApplyEligibility.isEligible(name));
        p.put("runningJobs", running);
        p.put("failures", failures);
        p.put("recoveryCount", recoveries);
        p.put("avgSubmissionTimeMs", avgSubmissionMs);
        // Per-provider verification timing isn't tracked separately from the global VerificationMetrics
        // average (SubmissionVerificationService doesn't record per-connector latency) — omitted rather
        // than fabricated.
        p.put("successRate", total == 0 ? null : (submitted * 100.0 / total));
        p.put("lastFailure", lastFailure.orElse(null));
        p.put("lastSuccess", lastSuccess.orElse(null));
        p.put("currentStatus", !configured ? "NOT_CONFIGURED" : (total == 0 ? "IDLE" : (failures * 100 > total * 30 ? "DEGRADED" : "HEALTHY")));
        return p;
    }

    private static boolean isRunning(String status) {
        return ApplicationExecution.STATUS_QUEUED.equals(status)
                || ApplicationExecution.STATUS_VALIDATING.equals(status)
                || ApplicationExecution.STATUS_EXECUTING.equals(status);
    }

    /** Queue Monitor — Objective 8. */
    public Map<String, Object> queues() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executionQueue", queueInfo(ApplicationExecution.STATUS_EXECUTING));
        out.put("retryQueue", queueInfo(ApplicationExecution.STATUS_RETRY));
        // Recovery Queue is the SAME underlying STATUS_RETRY set RecoveryScheduler polls — not a
        // second queue; reported alongside Retry Queue for the spec's naming, not duplicated storage.
        out.put("recoveryQueue", queueInfo(ApplicationExecution.STATUS_RETRY));
        out.put("manualQueue", queueInfo(ApplicationExecution.STATUS_MANUAL_REVIEW));
        Map<String, Object> waitingApprovalQueue = new LinkedHashMap<>();
        waitingApprovalQueue.put("items", approvals.countByStatus("PENDING"));
        waitingApprovalQueue.put("averageWaitMs", null);
        waitingApprovalQueue.put("oldestItem", null);
        waitingApprovalQueue.put("newestItem", null);
        waitingApprovalQueue.put("processingRate", null);
        out.put("waitingApprovalQueue", waitingApprovalQueue);
        out.put("runningQueue", queueInfo(ApplicationExecution.STATUS_EXECUTING));
        out.put("completedQueue", queueInfo(ApplicationExecution.STATUS_SUBMITTED));
        Map<String, Object> cancelledQueue = new LinkedHashMap<>();
        cancelledQueue.put("items", executions.countByExecutionStatusAndFailureReasonStartingWith(
                ApplicationExecution.STATUS_ABORTED, "cancelled by user:"));
        out.put("cancelledQueue", cancelledQueue);
        // P7 Action 4 — SUBMITTING was previously invisible on this dashboard entirely (no counter
        // anywhere). It reuses the same queueInfo shape every other status already gets — items,
        // oldest/newest, average wait — nothing SUBMITTING-specific was invented.
        out.put("submittingQueue", queueInfo(ApplicationExecution.STATUS_SUBMITTING));
        return out;
    }

    /**
     * P7 Action 4 — the actual rows behind {@code submittingQueue}, for human investigation. A row
     * here means the process died (or is still working) between the atomic claim (Action 1) and the
     * terminal write — genuinely ambiguous, since the browser click may or may not have reached the
     * employer. Deliberately read-only: this method only lists rows, exactly like {@link #detail}.
     * Nothing in this class — or anywhere reachable from it — automatically retries or transitions
     * a SUBMITTING row; see {@code STATUS_SUBMITTING}'s own javadoc for why that must stay true.
     */
    public List<Map<String, Object>> staleSubmittingExecutions(Duration staleAfter) {
        Instant cutoff = Instant.now().minus(staleAfter);
        return executions.findByExecutionStatusAndCreatedAtBefore(ApplicationExecution.STATUS_SUBMITTING, cutoff)
                .stream()
                .map(exec -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("executionId", exec.getId());
                    row.put("userId", exec.getUserId());
                    row.put("jobId", exec.getJobId());
                    row.put("createdAt", exec.getCreatedAt());
                    row.put("ageMs", Duration.between(exec.getCreatedAt(), Instant.now()).toMillis());
                    return row;
                })
                .toList();
    }

    private Map<String, Object> queueInfo(String status) {
        long items = executions.countByExecutionStatus(status);
        Instant oldest = executions.findFirstByExecutionStatusOrderByCreatedAtAsc(status)
                .map(ApplicationExecution::getCreatedAt).orElse(null);
        Instant newest = executions.findFirstByExecutionStatusOrderByCreatedAtDesc(status)
                .map(ApplicationExecution::getCreatedAt).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("oldestItem", oldest);
        m.put("newestItem", newest);
        m.put("averageWaitMs", (items > 0 && oldest != null) ? Duration.between(oldest, Instant.now()).toMillis() : null);
        // No time-series is retained (only current counts), so a genuine throughput rate can't be
        // computed honestly — left null rather than invented.
        m.put("processingRate", null);
        return m;
    }

    /** Application Detail Workspace — Objective 3, 4 (Timeline), 5 (Evidence), 6 (Recovery). */
    public Optional<Map<String, Object>> detail(UUID executionId, UUID userId) {
        ApplicationExecution exec = executions.findByIdAndUserId(executionId, userId).orElse(null);
        if (exec == null) return Optional.empty();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("execution", exec);
        out.put("timeline", timelineService.forJob(userId, exec.getJobId()));
        out.put("auditTrail", audit.findByApplicationExecutionIdOrderByCreatedAtAsc(executionId));
        out.put("screenshots", screenshots.findByExecutionIdOrderByCapturedAtAsc(executionId));
        out.put("retryHistory", retries.findByApplicationExecutionIdOrderByAttemptAsc(executionId));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("confirmationNumber", exec.getConfirmationNumber());
        evidence.put("verificationStatus", exec.getVerificationStatus());
        evidence.put("verificationMethod", exec.getVerificationMethod());
        evidence.put("verifiedAt", exec.getVerifiedAt());
        evidence.put("evidenceAgeMs", exec.getVerifiedAt() == null ? null
                : Duration.between(exec.getVerifiedAt(), Instant.now()).toMillis());
        out.put("evidence", evidence);
        return Optional.of(out);
    }

    /** Explainability — Objective 10. Derives answers purely from existing audit/timeline data; never generates separately. */
    public Optional<Map<String, Object>> explain(UUID executionId, UUID userId) {
        ApplicationExecution exec = executions.findByIdAndUserId(executionId, userId).orElse(null);
        if (exec == null) return Optional.empty();
        List<ApplicationExecutionAuditEntry> rows = audit.findByApplicationExecutionIdOrderByCreatedAtAsc(executionId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("currentStatus", exec.getExecutionStatus());
        out.put("whyRetried", reasonForOutcomePrefix(rows, "RECOVERY_RETRY"));
        out.put("whyPaused", reasonForOutcomePrefix(rows, "RECOVERY_PAUSE"));
        out.put("whyCancelled", ApplicationExecution.STATUS_ABORTED.equals(exec.getExecutionStatus())
                && exec.getFailureReason() != null && exec.getFailureReason().startsWith("cancelled by user:")
                ? exec.getFailureReason() : null);
        out.put("whyVerificationFailed", exec.getVerificationStatus() != null && !"VERIFIED".equals(exec.getVerificationStatus())
                ? reasonForOutcomePrefix(rows, "VERIFICATION_" + exec.getVerificationStatus()) : null);
        out.put("whyRecoverySucceeded", exec.getRetryOfExecutionId() != null
                && ApplicationExecution.STATUS_SUBMITTED.equals(exec.getExecutionStatus())
                ? "recovered from execution " + exec.getRetryOfExecutionId() : null);
        out.put("whyManualReviewRequired", ApplicationExecution.STATUS_MANUAL_REVIEW.equals(exec.getExecutionStatus())
                ? exec.getFailureReason() : null);
        return Optional.of(out);
    }

    private static String reasonForOutcomePrefix(List<ApplicationExecutionAuditEntry> rows, String outcomePrefix) {
        return rows.stream()
                .filter(r -> r.getOutcome() != null && r.getOutcome().startsWith(outcomePrefix))
                .map(ApplicationExecutionAuditEntry::getReason)
                .findFirst().orElse(null);
    }
}
