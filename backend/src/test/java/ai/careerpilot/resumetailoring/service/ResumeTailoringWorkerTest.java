package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.1.1 — the worker must hand off to {@link ResumeTailoringJobService#enqueue} (bounded
 * executor + observable job row) rather than calling {@link ResumeTailoringService#tailor}
 * directly (which used to run on Spring's unbounded default {@code @Async} executor).
 */
class ResumeTailoringWorkerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();
    private final UUID recommendationAuditId = UUID.randomUUID();

    @Test
    void enqueuesThroughJobServiceWhenTriggerAndEngineAreEnabled() {
        ResumeTailoringService tailoring = mock(ResumeTailoringService.class);
        ResumeTailoringJobService jobs = mock(ResumeTailoringJobService.class);
        when(tailoring.isEnabled()).thenReturn(true);
        ResumeTailoringWorker worker = new ResumeTailoringWorker(tailoring, jobs, true);

        worker.onRecommendationApproved(new RecommendationApprovedEvent(userId, orgId, jobId, applicationId, recommendationAuditId));

        verify(jobs).enqueue(userId, jobId, recommendationAuditId, ResumeTailoringJob.SOURCE_APPROVE_TRIGGER);
        verify(tailoring, never()).tailor(any(), any(), any());
    }

    @Test
    void isANoOpWhenTriggerFlagIsOff() {
        ResumeTailoringService tailoring = mock(ResumeTailoringService.class);
        ResumeTailoringJobService jobs = mock(ResumeTailoringJobService.class);
        when(tailoring.isEnabled()).thenReturn(true);
        ResumeTailoringWorker worker = new ResumeTailoringWorker(tailoring, jobs, false);

        worker.onRecommendationApproved(new RecommendationApprovedEvent(userId, orgId, jobId, applicationId, recommendationAuditId));

        verifyNoInteractions(jobs);
    }

    @Test
    void isANoOpWhenTheEngineItselfIsDisabled() {
        ResumeTailoringService tailoring = mock(ResumeTailoringService.class);
        ResumeTailoringJobService jobs = mock(ResumeTailoringJobService.class);
        when(tailoring.isEnabled()).thenReturn(false);
        ResumeTailoringWorker worker = new ResumeTailoringWorker(tailoring, jobs, true);

        worker.onRecommendationApproved(new RecommendationApprovedEvent(userId, orgId, jobId, applicationId, recommendationAuditId));

        verifyNoInteractions(jobs);
    }

    @Test
    void neverPropagatesAnExceptionFromEnqueue() {
        ResumeTailoringService tailoring = mock(ResumeTailoringService.class);
        ResumeTailoringJobService jobs = mock(ResumeTailoringJobService.class);
        when(tailoring.isEnabled()).thenReturn(true);
        when(jobs.enqueue(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        ResumeTailoringWorker worker = new ResumeTailoringWorker(tailoring, jobs, true);

        worker.onRecommendationApproved(new RecommendationApprovedEvent(userId, orgId, jobId, applicationId, recommendationAuditId));
        // no assertion needed beyond "did not throw" — failure isolation is the point
    }
}
