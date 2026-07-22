package ai.careerpilot.execution.recovery;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationRetry;
import ai.careerpilot.execution.browser.BrowserSessionManager;
import ai.careerpilot.execution.retry.RetryDecision;
import ai.careerpilot.execution.retry.RetryPolicyService;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 7.16.3 — the Automation Recovery Center's decision-to-state glue. {@link RetryPolicyService}
 * itself is mocked (its own exhaustive decision-matrix tests already live in
 * {@code RetryPolicyServiceTest}) — these tests only verify {@link AutomationRecoveryService} turns
 * each decision into the right {@link ApplicationExecution} mutation, never fabricating an outcome.
 */
class AutomationRecoveryServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID execId = UUID.randomUUID();

    private ApplicationExecutionRepository executions;
    private ApplicationExecutionAuditRepository audit;
    private RetryPolicyService retryPolicy;
    private TimelineService timeline;
    private BrowserSessionManager sessionManager;
    private RecoveryMetrics metrics;

    @BeforeEach
    void setUp() {
        executions = mock(ApplicationExecutionRepository.class);
        audit = mock(ApplicationExecutionAuditRepository.class);
        retryPolicy = mock(RetryPolicyService.class);
        timeline = mock(TimelineService.class);
        sessionManager = mock(BrowserSessionManager.class);
        metrics = new RecoveryMetrics();
        when(executions.save(any(ApplicationExecution.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private AutomationRecoveryService service(boolean enabled, boolean browserRestartEnabled) {
        return new AutomationRecoveryService(executions, audit, retryPolicy, timeline, sessionManager,
                metrics, enabled, browserRestartEnabled);
    }

    private ApplicationExecution exec(String status) {
        return ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(status).attemptCount(1)
                .build();
    }

    @Test
    void disabledIsANoOpAndReturnsEmpty() {
        Optional<RetryDecision> result = service(false, false).attemptRecovery(exec(ApplicationExecution.STATUS_EXECUTING), "network timeout");
        assertThat(result).isEmpty();
        verify(retryPolicy, never()).handleFailure(any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void retryDecisionParksExecutionAtRetryWithNextRetryAt() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_NETWORK, ApplicationRetry.ACTION_RETRY, 0L));
        ApplicationExecution e = exec(ApplicationExecution.STATUS_EXECUTING);

        Optional<RetryDecision> result = service(true, false).attemptRecovery(e, "connection timeout");

        assertThat(result).isPresent();
        assertThat(result.get().shouldRetry()).isTrue();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_RETRY);
        assertThat(e.getNextRetryAt()).isNotNull();
        assertThat(e.getFailureReason()).isEqualTo("connection timeout");
    }

    @Test
    void pauseDecisionParksExecutionAtManualReview() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_CAPTCHA, ApplicationRetry.ACTION_PAUSE, 0L));
        ApplicationExecution e = exec(ApplicationExecution.STATUS_EXECUTING);

        Optional<RetryDecision> result = service(true, false).attemptRecovery(e, "captcha detected");

        assertThat(result).isPresent();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_MANUAL_REVIEW);
    }

    @Test
    void stopDecisionDoesNotMutateExecution() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_VALIDATION_FAILED, ApplicationRetry.ACTION_STOP, 0L));
        ApplicationExecution e = exec(ApplicationExecution.STATUS_EXECUTING);

        Optional<RetryDecision> result = service(true, false).attemptRecovery(e, "invalid field");

        assertThat(result).isPresent();
        assertThat(result.get().shouldRetry()).isFalse();
        // execution status untouched — caller (ApplicationExecutionService#terminal) proceeds with FAILED
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_EXECUTING);
    }

    @Test
    void browserFailureTriggersZombieGatedRestartOnlyWhenFlagEnabled() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_BROWSER_FAILURE, ApplicationRetry.ACTION_RETRY, 0L));
        when(sessionManager.restartIfZombie()).thenReturn(true);

        service(true, true).attemptRecovery(exec(ApplicationExecution.STATUS_EXECUTING), "playwright crashed");

        verify(sessionManager).restartIfZombie();
        assertThat(metrics.snapshot().get("browserRestartCount")).isEqualTo(1L);
    }

    @Test
    void browserFailureNeverTouchesSessionManagerWhenRestartFlagDisabled() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_BROWSER_FAILURE, ApplicationRetry.ACTION_RETRY, 0L));

        service(true, false).attemptRecovery(exec(ApplicationExecution.STATUS_EXECUTING), "playwright crashed");

        verify(sessionManager, never()).restartIfZombie();
    }

    @Test
    void recordRecoveryOutcomeSuccessIncrementsSuccessMetric() {
        ApplicationExecution previous = exec(ApplicationExecution.STATUS_RETRIED);
        ApplicationExecution newAttempt = exec(ApplicationExecution.STATUS_SUBMITTED);

        service(true, false).recordRecoveryOutcome(previous, newAttempt);

        assertThat(metrics.snapshot().get("recoverySuccess")).isEqualTo(1L);
    }

    @Test
    void recordRecoveryOutcomeFailureIncrementsFailureMetric() {
        ApplicationExecution previous = exec(ApplicationExecution.STATUS_RETRIED);
        ApplicationExecution newAttempt = exec(ApplicationExecution.STATUS_FAILED);

        service(true, false).recordRecoveryOutcome(previous, newAttempt);

        assertThat(metrics.snapshot().get("recoveryFailure")).isEqualTo(1L);
    }

    @Test
    void recoveryNeverThrowsWhenTimelineOrAuditFail() {
        when(retryPolicy.handleFailure(any(), any(), any(), anyInt(), anyString()))
                .thenReturn(new RetryDecision(ApplicationRetry.CLASS_NETWORK, ApplicationRetry.ACTION_RETRY, 0L));
        when(audit.save(any())).thenThrow(new RuntimeException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                service(true, false).attemptRecovery(exec(ApplicationExecution.STATUS_EXECUTING), "timeout"));
    }
}
