package ai.careerpilot.learning.recommendation;

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

class RecommendationLearningWorkerTest {

    private RecommendationLearningService learningService;
    private LearningMetricsLogRepository metricsLog;
    private LearningMetrics metrics;
    private WorkflowDeadLetterService deadLetters;
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        learningService = mock(RecommendationLearningService.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        metrics = new LearningMetrics();
        deadLetters = mock(WorkflowDeadLetterService.class);
    }

    @Test
    void disabledWorkerNeverRecomputes() {
        RecommendationLearningWorker worker = new RecommendationLearningWorker(learningService, metricsLog, metrics, deadLetters, false);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verifyNoInteractions(learningService);
    }

    @Test
    void successPatternsUpdatedTriggersRecompute() {
        RecommendationLearningWorker worker = new RecommendationLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(learningService).recompute(userId);
        assertEquals(1, metrics.total("RECOMMENDATION_LEARNING"));
    }

    @Test
    void failurePatternsUpdatedAlsoTriggersRecompute() {
        RecommendationLearningWorker worker = new RecommendationLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onFailurePatternsUpdated(new FailurePatternsUpdatedEvent(correlationId, userId));
        verify(learningService).recompute(userId);
    }

    @Test
    void recomputeFailureIsIsolatedToDeadLetter() {
        doThrow(new RuntimeException("boom")).when(learningService).recompute(userId);
        RecommendationLearningWorker worker = new RecommendationLearningWorker(learningService, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(deadLetters).record(eq(correlationId), eq("LEARNING"), eq("RECOMMENDATION_LEARNING"), any(), any());
        assertEquals(1, metrics.failures("RECOMMENDATION_LEARNING"));
    }
}
