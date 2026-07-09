package ai.careerpilot.review;

import ai.careerpilot.packageintel.event.ApplicationPackageValidatedEvent;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The worker must fire only when both flags are on, run on its executor, and dead-letter failures. */
class ApplicationReviewWorkerTest {

    private ApplicationReviewPipeline pipeline;
    private ThreadPoolTaskExecutor executor;
    private WorkflowCorrelationService correlation;
    private WorkflowDeadLetterService deadLetter;

    private final UUID cid = UUID.randomUUID();
    private final ApplicationPackageValidatedEvent event =
            new ApplicationPackageValidatedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "READY", null);

    @BeforeEach
    void setUp() {
        pipeline = mock(ApplicationReviewPipeline.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        correlation = mock(WorkflowCorrelationService.class);
        deadLetter = mock(WorkflowDeadLetterService.class);
        when(correlation.start(any(), any(), any(), any())).thenReturn(cid);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(executor).execute(any());
    }

    private ApplicationReviewWorker worker(boolean trigger, boolean engineEnabled) {
        when(pipeline.isEnabled()).thenReturn(engineEnabled);
        return new ApplicationReviewWorker(pipeline, executor, correlation, deadLetter, trigger);
    }

    @Test
    void darkByDefaultDoesNothing() {
        worker(false, false).onApplicationPackageValidated(event);
        verifyNoInteractions(executor);
        verify(pipeline, never()).review(any(), any());
    }

    @Test
    void triggerOnEngineOffDoesNothing() {
        worker(true, false).onApplicationPackageValidated(event);
        verify(pipeline, never()).review(any(), any());
    }

    @Test
    void bothOnReviewsOnExecutor() {
        worker(true, true).onApplicationPackageValidated(event);
        verify(executor).execute(any());
        verify(pipeline).review(eq(event.applicationPackageId()), any());
    }

    @Test
    void taskFailureIsDeadLettered() {
        ApplicationReviewWorker w = worker(true, true);
        when(pipeline.review(any(), any())).thenThrow(new RuntimeException("boom"));
        w.onApplicationPackageValidated(event); // must not throw
        verify(deadLetter).record(any(), eq("APPLICATION_REVIEW"), eq("AI_REVIEW"), any(), any());
    }
}
