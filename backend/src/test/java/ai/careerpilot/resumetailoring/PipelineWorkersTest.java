package ai.careerpilot.resumetailoring;

import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageService;
import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageWorker;
import ai.careerpilot.resumetailoring.autoapply.AutoApplyPreparationService;
import ai.careerpilot.resumetailoring.autoapply.AutoApplyPreparationWorker;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterService;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterWorker;
import ai.careerpilot.resumetailoring.event.*;
import ai.careerpilot.resumetailoring.explain.AtsExplainabilityService;
import ai.careerpilot.resumetailoring.explain.AtsExplainabilityWorker;
import ai.careerpilot.resumetailoring.gap.GapAnalysisService;
import ai.careerpilot.resumetailoring.gap.GapAnalysisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.3–2D.7 — the five pipeline workers share one contract: run the stage on its OWN
 * bounded executor, only when both the trigger flag and the engine flag are on, and never let any
 * failure (including a saturated queue's {@link TaskRejectedException}) propagate back into the
 * publishing flow. This suite verifies that contract per worker, with the executor mocked to run
 * submitted work inline so each service interaction is observable synchronously.
 */
class PipelineWorkersTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID atsAnalysisId = UUID.randomUUID();
    private final UUID gapAnalysisId = UUID.randomUUID();
    private final UUID explanationId = UUID.randomUUID();
    private final UUID coverLetterId = UUID.randomUUID();
    private final UUID packageId = UUID.randomUUID();

    /** Executor mock that runs every submitted task inline (synchronously). */
    private static ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));
        return executor;
    }

    /** Executor mock whose queue is saturated — every submit throws. */
    private static ThreadPoolTaskExecutor rejectingExecutor() {
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doThrow(new TaskRejectedException("queue full")).when(executor).execute(any(Runnable.class));
        return executor;
    }

    // ── 2D.3 Gap Analysis ──

    @Test
    void gapWorkerRunsAnalysisOnItsExecutorWhenBothFlagsOn() {
        GapAnalysisService service = mock(GapAnalysisService.class);
        when(service.isEnabled()).thenReturn(true);
        new GapAnalysisWorker(service, inlineExecutor(), true)
                .onAtsOptimized(new AtsOptimizedEvent(userId, jobId, tailoringId, atsAnalysisId));
        verify(service).analyze(userId, jobId, tailoringId, atsAnalysisId);
    }

    @Test
    void gapWorkerIsANoOpWhenTriggerOrEngineIsOff() {
        GapAnalysisService service = mock(GapAnalysisService.class);
        when(service.isEnabled()).thenReturn(true);
        new GapAnalysisWorker(service, inlineExecutor(), false)
                .onAtsOptimized(new AtsOptimizedEvent(userId, jobId, tailoringId, atsAnalysisId));
        verify(service, never()).analyze(any(), any(), any(), any());

        GapAnalysisService disabled = mock(GapAnalysisService.class);
        when(disabled.isEnabled()).thenReturn(false);
        new GapAnalysisWorker(disabled, inlineExecutor(), true)
                .onAtsOptimized(new AtsOptimizedEvent(userId, jobId, tailoringId, atsAnalysisId));
        verify(disabled, never()).analyze(any(), any(), any(), any());
    }

    @Test
    void gapWorkerSwallowsASaturatedQueueRejection() {
        GapAnalysisService service = mock(GapAnalysisService.class);
        when(service.isEnabled()).thenReturn(true);
        new GapAnalysisWorker(service, rejectingExecutor(), true)
                .onAtsOptimized(new AtsOptimizedEvent(userId, jobId, tailoringId, atsAnalysisId));
        // no throw = failure isolated; the stage is skipped, the publisher is unaffected
    }

    // ── 2D.4 ATS Explainability ──

    @Test
    void explainabilityWorkerRunsOnItsExecutorWhenBothFlagsOn() {
        AtsExplainabilityService service = mock(AtsExplainabilityService.class);
        when(service.isEnabled()).thenReturn(true);
        new AtsExplainabilityWorker(service, inlineExecutor(), true)
                .onGapAnalysisCompleted(new GapAnalysisCompletedEvent(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId));
        verify(service).explain(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId);
    }

    @Test
    void explainabilityWorkerIsANoOpWhenFlagsOffAndSwallowsRejection() {
        AtsExplainabilityService service = mock(AtsExplainabilityService.class);
        when(service.isEnabled()).thenReturn(false);
        new AtsExplainabilityWorker(service, inlineExecutor(), true)
                .onGapAnalysisCompleted(new GapAnalysisCompletedEvent(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId));
        verify(service, never()).explain(any(), any(), any(), any(), any());

        AtsExplainabilityService enabled = mock(AtsExplainabilityService.class);
        when(enabled.isEnabled()).thenReturn(true);
        new AtsExplainabilityWorker(enabled, rejectingExecutor(), true)
                .onGapAnalysisCompleted(new GapAnalysisCompletedEvent(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId));
    }

    // ── 2D.5 Cover Letter ──

    @Test
    void coverLetterWorkerRunsOnItsExecutorWhenBothFlagsOn() {
        CoverLetterService service = mock(CoverLetterService.class);
        when(service.isEnabled()).thenReturn(true);
        new CoverLetterWorker(service, inlineExecutor(), true)
                .onAtsExplainabilityCompleted(new AtsExplainabilityCompletedEvent(
                        userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId, explanationId));
        verify(service).generate(userId, jobId, tailoringId);
    }

    @Test
    void coverLetterWorkerIsANoOpWhenFlagsOffAndSwallowsRejection() {
        CoverLetterService service = mock(CoverLetterService.class);
        when(service.isEnabled()).thenReturn(true);
        new CoverLetterWorker(service, inlineExecutor(), false)
                .onAtsExplainabilityCompleted(new AtsExplainabilityCompletedEvent(
                        userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId, explanationId));
        verify(service, never()).generate(any(), any(), any());

        CoverLetterService enabled = mock(CoverLetterService.class);
        when(enabled.isEnabled()).thenReturn(true);
        new CoverLetterWorker(enabled, rejectingExecutor(), true)
                .onAtsExplainabilityCompleted(new AtsExplainabilityCompletedEvent(
                        userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId, explanationId));
    }

    // ── 2D.6 Application Package ──

    @Test
    void packageWorkerRunsOnItsExecutorWhenBothFlagsOn() {
        ApplicationPackageService service = mock(ApplicationPackageService.class);
        when(service.isEnabled()).thenReturn(true);
        new ApplicationPackageWorker(service, inlineExecutor(), true)
                .onCoverLetterCompleted(new CoverLetterCompletedEvent(userId, jobId, tailoringId, coverLetterId, 1));
        verify(service).assemble(userId, jobId);
    }

    @Test
    void packageWorkerIsANoOpWhenFlagsOffAndSwallowsRejection() {
        ApplicationPackageService service = mock(ApplicationPackageService.class);
        when(service.isEnabled()).thenReturn(false);
        new ApplicationPackageWorker(service, inlineExecutor(), true)
                .onCoverLetterCompleted(new CoverLetterCompletedEvent(userId, jobId, tailoringId, coverLetterId, 1));
        verify(service, never()).assemble(any(), any());

        ApplicationPackageService enabled = mock(ApplicationPackageService.class);
        when(enabled.isEnabled()).thenReturn(true);
        new ApplicationPackageWorker(enabled, rejectingExecutor(), true)
                .onCoverLetterCompleted(new CoverLetterCompletedEvent(userId, jobId, tailoringId, coverLetterId, 1));
    }

    // ── 2D.7 Auto Apply Preparation ──

    @Test
    void autoApplyWorkerRunsOnItsExecutorWhenBothFlagsOn() {
        AutoApplyPreparationService service = mock(AutoApplyPreparationService.class);
        when(service.isEnabled()).thenReturn(true);
        new AutoApplyPreparationWorker(service, inlineExecutor(), true)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, packageId, 1));
        verify(service).prepare(userId, jobId, packageId);
    }

    @Test
    void autoApplyWorkerIsANoOpWhenFlagsOffAndSwallowsRejection() {
        AutoApplyPreparationService service = mock(AutoApplyPreparationService.class);
        when(service.isEnabled()).thenReturn(true);
        new AutoApplyPreparationWorker(service, inlineExecutor(), false)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, packageId, 1));
        verify(service, never()).prepare(any(), any(), any());

        AutoApplyPreparationService enabled = mock(AutoApplyPreparationService.class);
        when(enabled.isEnabled()).thenReturn(true);
        new AutoApplyPreparationWorker(enabled, rejectingExecutor(), true)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, packageId, 1));
    }
}
