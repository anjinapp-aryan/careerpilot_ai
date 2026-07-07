package ai.careerpilot.learning.career;

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

class CareerLearningWorkerTest {

    private CareerLearningEngine learningEngine;
    private CareerStrategyEngine strategyEngine;
    private LearningMetricsLogRepository metricsLog;
    private LearningMetrics metrics;
    private WorkflowDeadLetterService deadLetters;
    private final UUID userId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        learningEngine = mock(CareerLearningEngine.class);
        strategyEngine = mock(CareerStrategyEngine.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        metrics = new LearningMetrics();
        deadLetters = mock(WorkflowDeadLetterService.class);
    }

    @Test
    void disabledWorkerNeverRecomputes() {
        CareerLearningWorker worker = new CareerLearningWorker(learningEngine, strategyEngine, metricsLog, metrics, deadLetters, false);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verifyNoInteractions(learningEngine, strategyEngine);
    }

    @Test
    void successPatternsUpdatedRecomputesBothLearningAndStrategy() {
        CareerLearningWorker worker = new CareerLearningWorker(learningEngine, strategyEngine, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(learningEngine).recompute(userId);
        verify(strategyEngine).recompute(userId);
        assertEquals(1, metrics.total("CAREER_LEARNING"));
    }

    @Test
    void failurePatternsUpdatedAlsoRecomputes() {
        CareerLearningWorker worker = new CareerLearningWorker(learningEngine, strategyEngine, metricsLog, metrics, deadLetters, true);
        worker.onFailurePatternsUpdated(new FailurePatternsUpdatedEvent(correlationId, userId));
        verify(learningEngine).recompute(userId);
    }

    @Test
    void learningEngineFailureIsIsolatedToDeadLetter() {
        doThrow(new RuntimeException("boom")).when(learningEngine).recompute(userId);
        CareerLearningWorker worker = new CareerLearningWorker(learningEngine, strategyEngine, metricsLog, metrics, deadLetters, true);
        worker.onSuccessPatternsUpdated(new SuccessPatternsUpdatedEvent(correlationId, userId));
        verify(deadLetters).record(eq(correlationId), eq("LEARNING"), eq("CAREER_LEARNING"), any(), any());
        verify(strategyEngine, never()).recompute(any());
    }
}
