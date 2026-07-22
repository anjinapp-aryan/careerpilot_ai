package ai.careerpilot.execution.operations;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.execution.ApplicationExecutionMetrics;
import ai.careerpilot.execution.recovery.RecoveryMetrics;
import ai.careerpilot.execution.verification.VerificationMetrics;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationRetryRepository;
import ai.careerpilot.repo.ApprovalQueueRepository;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7.16.4 — the Operations Center's aggregation layer. Every assertion here checks that a
 * displayed number is a genuine derivation of mocked repo/metric data, never a fabricated value.
 */
class OperationsServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID execId = UUID.randomUUID();

    private ApplicationExecutionRepository executions;
    private ApplicationExecutionAuditRepository audit;
    private ApplicationRetryRepository retries;
    private ExecutionScreenshotRepository screenshots;
    private ApprovalQueueRepository approvals;
    private ATSConnectorRegistry atsRegistry;
    private TimelineService timelineService;
    private ApplicationExecutionMetrics executionMetrics;
    private VerificationMetrics verificationMetrics;
    private RecoveryMetrics recoveryMetrics;
    private OperationsService service;

    @BeforeEach
    void setUp() {
        executions = mock(ApplicationExecutionRepository.class);
        audit = mock(ApplicationExecutionAuditRepository.class);
        retries = mock(ApplicationRetryRepository.class);
        screenshots = mock(ExecutionScreenshotRepository.class);
        approvals = mock(ApprovalQueueRepository.class);
        atsRegistry = mock(ATSConnectorRegistry.class);
        timelineService = mock(TimelineService.class);
        executionMetrics = mock(ApplicationExecutionMetrics.class);
        verificationMetrics = mock(VerificationMetrics.class);
        recoveryMetrics = mock(RecoveryMetrics.class);
        service = new OperationsService(executions, audit, retries, screenshots, approvals, atsRegistry,
                timelineService, executionMetrics, verificationMetrics, recoveryMetrics);

        when(executionMetrics.snapshot()).thenReturn(java.util.Map.of("applicationExecutionAvgLatencyMs", 1200L));
        when(verificationMetrics.snapshot()).thenReturn(java.util.Map.of(
                "avgVerificationLatencyMs", 300.0, "verificationSuccessRate", 80.0));
        when(recoveryMetrics.snapshot()).thenReturn(java.util.Map.of(
                "avgRecoveryLatencyMs", 500.0, "recoverySuccessRate", 60.0));
        when(atsRegistry.all()).thenReturn(List.of());
    }

    // ── summary() ──

    @Test
    void summaryComputesRunningAsSumOfThreeStatuses() {
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_QUEUED)).thenReturn(2L);
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_VALIDATING)).thenReturn(3L);
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_EXECUTING)).thenReturn(5L);

        var out = service.summary();

        assertThat(out.get("running")).isEqualTo(10L);
    }

    @Test
    void summarySplitsSubmittedIntoCompletedAndVerificationFailed() {
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_SUBMITTED)).thenReturn(10L);
        when(executions.countByExecutionStatusAndVerificationStatus(ApplicationExecution.STATUS_SUBMITTED, "VERIFIED"))
                .thenReturn(6L);
        when(executions.countByExecutionStatusAndVerificationStatusIsNull(ApplicationExecution.STATUS_SUBMITTED))
                .thenReturn(1L); // never-verified (e.g. verification threw) — still counted as completed, not failed

        var out = service.summary();

        assertThat(out.get("completed")).isEqualTo(7L);
        assertThat(out.get("verificationFailed")).isEqualTo(3L);
    }

    @Test
    void summaryVerificationPendingIsAlwaysZero() {
        // Verification is synchronous — there is no persisted "pending" window, ever.
        var out = service.summary();
        assertThat(out.get("verificationPending")).isEqualTo(0L);
    }

    @Test
    void summaryReusesExistingMetricsSnapshotsVerbatim() {
        var out = service.summary();

        assertThat(out.get("avgSubmissionTimeMs")).isEqualTo(1200L);
        assertThat(out.get("avgVerificationTimeMs")).isEqualTo(300.0);
        assertThat(out.get("avgRecoveryTimeMs")).isEqualTo(500.0);
        assertThat(out.get("recoverySuccessRate")).isEqualTo(60.0);
        assertThat(out.get("verificationSuccessRate")).isEqualTo(80.0);
    }

    @Test
    void automationSuccessRateIsZeroWhenNoTerminalExecutionsExist() {
        var out = service.summary();
        assertThat(out.get("automationSuccessRate")).isEqualTo(0.0);
    }

    // ── fleet() ──

    @Test
    void fleetIncludesEveryRegisteredConnectorEvenWithZeroExecutions() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.name()).thenReturn("greenhouse");
        when(connector.isConfigured()).thenReturn(true);
        when(atsRegistry.all()).thenReturn(List.of(connector));
        when(executions.findTop1000ByProviderIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of());

        var out = service.fleet();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> providers = (List<java.util.Map<String, Object>>) out.get("providers");

        assertThat(providers).hasSize(1);
        assertThat(providers.get(0).get("provider")).isEqualTo("greenhouse");
        assertThat(providers.get(0).get("currentStatus")).isEqualTo("IDLE");
    }

    @Test
    void fleetBucketsUnattributedProviderNamesAsUnknown() {
        ApplicationExecution row = ApplicationExecution.builder()
                .id(UUID.randomUUID()).provider("some-removed-connector")
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED).createdAt(Instant.now()).build();
        when(executions.findTop1000ByProviderIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of(row));
        when(atsRegistry.all()).thenReturn(List.of());

        var out = service.fleet();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> providers = (List<java.util.Map<String, Object>>) out.get("providers");

        assertThat(providers).extracting(p -> p.get("provider")).contains("unknown");
    }

    @Test
    void fleetComputesSuccessRateFromRealCounts() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.name()).thenReturn("lever");
        when(connector.isConfigured()).thenReturn(true);
        when(atsRegistry.all()).thenReturn(List.of(connector));
        ApplicationExecution submitted = ApplicationExecution.builder()
                .id(UUID.randomUUID()).provider("lever").executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .createdAt(Instant.now()).build();
        ApplicationExecution failed = ApplicationExecution.builder()
                .id(UUID.randomUUID()).provider("lever").executionStatus(ApplicationExecution.STATUS_FAILED)
                .createdAt(Instant.now()).build();
        when(executions.findTop1000ByProviderIsNotNullOrderByCreatedAtDesc()).thenReturn(List.of(submitted, failed));

        var out = service.fleet();
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> providers = (List<java.util.Map<String, Object>>) out.get("providers");

        assertThat(providers.get(0).get("successRate")).isEqualTo(50.0);
        assertThat(providers.get(0).get("failures")).isEqualTo(1L);
    }

    // ── queues() ──

    @Test
    void queuesReportsItemsOldestAndNewest() {
        Instant oldest = Instant.now().minusSeconds(3600);
        Instant newest = Instant.now();
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_RETRY)).thenReturn(4L);
        when(executions.findFirstByExecutionStatusOrderByCreatedAtAsc(ApplicationExecution.STATUS_RETRY))
                .thenReturn(Optional.of(ApplicationExecution.builder().createdAt(oldest).build()));
        when(executions.findFirstByExecutionStatusOrderByCreatedAtDesc(ApplicationExecution.STATUS_RETRY))
                .thenReturn(Optional.of(ApplicationExecution.builder().createdAt(newest).build()));

        var out = service.queues();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> retryQueue = (java.util.Map<String, Object>) out.get("retryQueue");

        assertThat(retryQueue.get("items")).isEqualTo(4L);
        assertThat(retryQueue.get("oldestItem")).isEqualTo(oldest);
        assertThat(retryQueue.get("newestItem")).isEqualTo(newest);
        assertThat(retryQueue.get("processingRate")).isNull(); // honestly not computed
    }

    @Test
    void queuesEmptyQueueHasNullAverageWait() {
        when(executions.countByExecutionStatus(ApplicationExecution.STATUS_MANUAL_REVIEW)).thenReturn(0L);
        when(executions.findFirstByExecutionStatusOrderByCreatedAtAsc(ApplicationExecution.STATUS_MANUAL_REVIEW))
                .thenReturn(Optional.empty());

        var out = service.queues();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> manualQueue = (java.util.Map<String, Object>) out.get("manualQueue");

        assertThat(manualQueue.get("averageWaitMs")).isNull();
    }

    // ── detail() / explain() ──

    @Test
    void detailIsEmptyWhenExecutionNotFoundOrNotOwned() {
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.empty());
        assertThat(service.detail(execId, userId)).isEmpty();
    }

    @Test
    void detailAggregatesTimelineAuditScreenshotsAndRetryHistory() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .confirmationNumber("conf-1").verificationStatus("VERIFIED").verifiedAt(Instant.now())
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(exec));
        when(timelineService.forJob(userId, jobId)).thenReturn(List.of());
        when(audit.findByApplicationExecutionIdOrderByCreatedAtAsc(execId)).thenReturn(List.of());
        when(screenshots.findByExecutionIdOrderByCapturedAtAsc(execId)).thenReturn(List.of());
        when(retries.findByApplicationExecutionIdOrderByAttemptAsc(execId)).thenReturn(List.of());

        var result = service.detail(execId, userId);

        assertThat(result).isPresent();
        assertThat(result.get().get("execution")).isEqualTo(exec);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> evidence = (java.util.Map<String, Object>) result.get().get("evidence");
        assertThat(evidence.get("confirmationNumber")).isEqualTo("conf-1");
    }

    @Test
    void explainIsEmptyWhenExecutionNotFoundOrNotOwned() {
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.empty());
        assertThat(service.explain(execId, userId)).isEmpty();
    }

    @Test
    void explainDerivesWhyRetriedFromAuditReasonNeverFabricated() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_RETRY)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(exec));
        when(audit.findByApplicationExecutionIdOrderByCreatedAtAsc(execId)).thenReturn(List.of(
                ApplicationExecutionAuditEntry.builder().outcome("RECOVERY_RETRY").reason("connection timeout").build()));

        var result = service.explain(execId, userId).orElseThrow();

        assertThat(result.get("whyRetried")).isEqualTo("connection timeout");
        assertThat(result.get("whyPaused")).isNull();
    }

    @Test
    void explainDerivesWhyCancelledOnlyFromRealCancellationReason() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("cancelled by user: changed my mind")
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(exec));
        when(audit.findByApplicationExecutionIdOrderByCreatedAtAsc(execId)).thenReturn(List.of());

        var result = service.explain(execId, userId).orElseThrow();

        assertThat(result.get("whyCancelled")).isEqualTo("cancelled by user: changed my mind");
    }

    @Test
    void explainNeverInventsWhyRecoverySucceededUnlessGenuinelyRecoveredAndSubmitted() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_FAILED) // not SUBMITTED
                .retryOfExecutionId(UUID.randomUUID())
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(exec));
        when(audit.findByApplicationExecutionIdOrderByCreatedAtAsc(execId)).thenReturn(List.of());

        var result = service.explain(execId, userId).orElseThrow();

        assertThat(result.get("whyRecoverySucceeded")).isNull();
    }
}
