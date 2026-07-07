package ai.careerpilot.learning.resume;

import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.event.FailurePatternsUpdatedEvent;
import ai.careerpilot.learning.event.SuccessPatternsUpdatedEvent;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResumeLearningWorkerTest {

    private ResumeLearningService learningService;
    private LearningMetricsLogRepository metricsLog;
    private LearningMetrics metrics;
    private WorkflowDeadLetterService deadLetters;
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        learningService = mock(ResumeLearningService.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        metrics = new LearningMetrics();
        deadLetters = mock(WorkflowDeadLetterService.class);
    }

    @Test
    void disabledWorkerNeverRecomputes() {
        ResumeLearningWorker worker = new ResumeLearningWorker(learningService, metricsLog, metrics, deadLetters, false);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verifyNoInteractions(learningService);
    }

    @Test
    void successPatternsUpdatedTriggersRecompute() {
        ResumeLearningWorker worker = new ResumeLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(learningService).recompute(userId);
        assertEquals(1, metrics.total("RESUME_LEARNING"));
    }

    @Test
    void failurePatternsUpdatedAlsoTriggersRecompute() {
        ResumeLearningWorker worker = new ResumeLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onFailurePatternsUpdated(new FailurePatternsUpdatedEvent(correlationId, userId));
        verify(learningService).recompute(userId);
    }

    @Test
    void recomputeFailureIsIsolatedToDeadLetter() {
        doThrow(new RuntimeException("boom")).when(learningService).recompute(userId);
        ResumeLearningWorker worker = new ResumeLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(deadLetters).record(eq(correlationId), eq("LEARNING"), eq("RESUME_LEARNING"), any(), any());
    }
}
