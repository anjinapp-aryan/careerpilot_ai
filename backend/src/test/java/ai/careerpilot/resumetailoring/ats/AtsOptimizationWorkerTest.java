package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.resumetailoring.event.ResumeTailoredEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.2 — {@link AtsOptimizationWorker} must enqueue through {@link
 * AtsOptimizationJobService#enqueue} (bounded executor + observable job row) only when both the
 * trigger flag and the engine itself are enabled, and must never propagate a failure back into
 * the tailoring flow that published the event.
 */
class AtsOptimizationWorkerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID resumeTailoringId = UUID.randomUUID();
    private final UUID recommendationAuditId = UUID.randomUUID();

    @Test
    void enqueuesThroughJobServiceWhenTriggerAndEngineAreEnabled() {
        AtsOptimizationService optimization = mock(AtsOptimizationService.class);
        AtsOptimizationJobService jobs = mock(AtsOptimizationJobService.class);
        when(optimization.isEnabled()).thenReturn(true);
        AtsOptimizationWorker worker = new AtsOptimizationWorker(optimization, jobs, true);

        worker.onResumeTailored(new ResumeTailoredEvent(userId, jobId, resumeTailoringId, recommendationAuditId));

        verify(jobs).enqueue(userId, jobId, AtsOptimizationJob.SOURCE_TAILORING_TRIGGER);
    }

    @Test
    void isANoOpWhenTriggerFlagIsOff() {
        AtsOptimizationService optimization = mock(AtsOptimizationService.class);
        AtsOptimizationJobService jobs = mock(AtsOptimizationJobService.class);
        when(optimization.isEnabled()).thenReturn(true);
        AtsOptimizationWorker worker = new AtsOptimizationWorker(optimization, jobs, false);

        worker.onResumeTailored(new ResumeTailoredEvent(userId, jobId, resumeTailoringId, recommendationAuditId));

        verifyNoInteractions(jobs);
    }

    @Test
    void isANoOpWhenTheEngineItselfIsDisabled() {
        AtsOptimizationService optimization = mock(AtsOptimizationService.class);
        AtsOptimizationJobService jobs = mock(AtsOptimizationJobService.class);
        when(optimization.isEnabled()).thenReturn(false);
        AtsOptimizationWorker worker = new AtsOptimizationWorker(optimization, jobs, true);

        worker.onResumeTailored(new ResumeTailoredEvent(userId, jobId, resumeTailoringId, recommendationAuditId));

        verifyNoInteractions(jobs);
    }

    @Test
    void neverPropagatesAnExceptionFromEnqueue() {
        AtsOptimizationService optimization = mock(AtsOptimizationService.class);
        AtsOptimizationJobService jobs = mock(AtsOptimizationJobService.class);
        when(optimization.isEnabled()).thenReturn(true);
        when(jobs.enqueue(any(), any(), any())).thenThrow(new RuntimeException("boom"));
        AtsOptimizationWorker worker = new AtsOptimizationWorker(optimization, jobs, true);

        worker.onResumeTailored(new ResumeTailoredEvent(userId, jobId, resumeTailoringId, recommendationAuditId));
        // no assertion needed beyond "did not throw" — failure isolation is the point
    }
}
