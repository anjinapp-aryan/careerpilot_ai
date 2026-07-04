package ai.careerpilot.retention;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import ai.careerpilot.repo.ResumeTailoringAuditRepository;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Retention is additive, flag-gated maintenance: with stock defaults it deletes nothing; enabled, it
 * purges by age per target, guards correlations to terminal statuses only, and isolates per-target
 * failures so one failing purge never aborts the others.
 */
class RetentionServiceTest {

    private final WorkflowDeadLetterRepository deadLetters = mock(WorkflowDeadLetterRepository.class);
    private final WorkflowCorrelationRepository correlations = mock(WorkflowCorrelationRepository.class);
    private final RecommendationAuditRepository recommendationAudits = mock(RecommendationAuditRepository.class);
    private final ApplicationExecutionAuditRepository executionAudits = mock(ApplicationExecutionAuditRepository.class);
    private final ResumeTailoringAuditRepository resumeTailoringAudits = mock(ResumeTailoringAuditRepository.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    private RetentionService service() {
        return new RetentionService(deadLetters, correlations, recommendationAudits,
                executionAudits, resumeTailoringAudits, txManager);
    }

    @Test
    void disabledByDefaultDeletesNothing() {
        RetentionService s = service(); // enabled defaults to false
        Map<String, Long> result = s.purgeAll();
        assertThat(s.isEnabled()).isFalse();
        assertThat(result).isEmpty();
        verifyNoInteractions(deadLetters, correlations, recommendationAudits, executionAudits, resumeTailoringAudits);
    }

    @Test
    void enabledPurgesEveryTargetByAge() {
        RetentionService s = service();
        enable(s);
        when(deadLetters.deleteByCreatedAtBefore(any())).thenReturn(3L);
        when(correlations.deleteByStatusInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(2L);
        when(recommendationAudits.deleteByCreatedAtBefore(any())).thenReturn(5L);
        when(executionAudits.deleteByCreatedAtBefore(any())).thenReturn(1L);
        when(resumeTailoringAudits.deleteByCreatedAtBefore(any())).thenReturn(4L);

        Map<String, Long> result = s.purgeAll();

        assertThat(result).containsEntry("workflow_dead_letter", 3L)
                .containsEntry("workflow_correlation", 2L)
                .containsEntry("recommendation_audit", 5L)
                .containsEntry("execution_audit", 1L)
                .containsEntry("resume_tailoring_audit", 4L);
    }

    @Test
    void correlationPurgeIsGuardedToTerminalStatusesOnly() {
        RetentionService s = service();
        enable(s);
        s.purgeAll();
        verify(correlations).deleteByStatusInAndUpdatedAtBefore(argThatTerminalOnly(), any());
    }

    @Test
    void perTargetFailureIsIsolated() {
        RetentionService s = service();
        enable(s);
        when(deadLetters.deleteByCreatedAtBefore(any())).thenThrow(new RuntimeException("db down"));
        when(recommendationAudits.deleteByCreatedAtBefore(any())).thenReturn(7L);

        Map<String, Long> result = s.purgeAll();

        assertThat(result).containsEntry("workflow_dead_letter", -1L); // caught, marked, not rethrown
        assertThat(result).containsEntry("recommendation_audit", 7L);  // other targets still ran
    }

    private static void enable(RetentionService s) {
        ReflectionTestUtils.setField(s, "enabled", true);
        ReflectionTestUtils.setField(s, "deadLetterDays", 90);
        ReflectionTestUtils.setField(s, "correlationDays", 180);
        ReflectionTestUtils.setField(s, "recommendationAuditDays", 365);
        ReflectionTestUtils.setField(s, "executionAuditDays", 365);
        ReflectionTestUtils.setField(s, "resumeTailoringAuditDays", 365);
    }

    private static Collection<String> argThatTerminalOnly() {
        return org.mockito.ArgumentMatchers.argThat(c ->
                c != null && c.containsAll(java.util.List.of(
                        WorkflowCorrelation.STATUS_COMPLETED, WorkflowCorrelation.STATUS_FAILED,
                        WorkflowCorrelation.STATUS_DEAD_LETTERED))
                && !c.contains(WorkflowCorrelation.STATUS_STARTED)
                && !c.contains(WorkflowCorrelation.STATUS_IN_PROGRESS));
    }

    // guard against accidental deletes on the disabled path being wired to the scheduler-only trigger
    @Test
    void enabledButAllZeroWhenNothingOldEnough() {
        RetentionService s = service();
        enable(s);
        when(deadLetters.deleteByCreatedAtBefore(any())).thenReturn(0L);
        when(correlations.deleteByStatusInAndUpdatedAtBefore(anyCollection(), any())).thenReturn(0L);
        when(recommendationAudits.deleteByCreatedAtBefore(any())).thenReturn(0L);
        when(executionAudits.deleteByCreatedAtBefore(any())).thenReturn(0L);
        when(resumeTailoringAudits.deleteByCreatedAtBefore(any())).thenReturn(0L);
        assertThat(s.purgeAll().values()).allMatch(v -> v == 0L);
        verify(deadLetters, never()).deleteAll();
    }

    @Test
    void cutoffIsInThePast() {
        RetentionService s = service();
        enable(s);
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        s.purgeAll();
        verify(deadLetters).deleteByCreatedAtBefore(captor.capture());
        assertThat(captor.getValue()).isBefore(Instant.now());
    }
}
