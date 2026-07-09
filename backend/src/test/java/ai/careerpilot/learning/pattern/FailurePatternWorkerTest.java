package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.event.FailurePatternsUpdatedEvent;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailurePatternWorkerTest {

    private FailurePatternEngine engine;
    private LearningEventRepository events;
    private LearningMetricsLogRepository metricsLog;
    private LearningMetrics metrics;
    private WorkflowDeadLetterService deadLetters;
    private ApplicationEventPublisher publisher;
    private FailurePatternWorker worker;

    private final UUID userId = UUID.randomUUID();
    private final UUID learningEventId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        engine = mock(FailurePatternEngine.class);
        events = mock(LearningEventRepository.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        metrics = new LearningMetrics();
        deadLetters = mock(WorkflowDeadLetterService.class);
        publisher = mock(ApplicationEventPublisher.class);
        worker = new FailurePatternWorker(engine, events, metricsLog, metrics, deadLetters, publisher);
    }

    @Test
    void disabledEngineSkipsEntirely() {
        when(engine.isEnabled()).thenReturn(false);
        worker.onLearningEventRecorded(new LearningEventRecordedEvent(learningEventId, correlationId, userId, null, LearningEventType.APPLICATION_REJECTED));
        verifyNoInteractions(events, publisher);
    }

    @Test
    void successfulAnalysisPublishesUpdateEvent() {
        LearningEvent learningEvent = LearningEvent.builder().id(learningEventId).userId(userId).build();
        when(engine.isEnabled()).thenReturn(true);
        when(events.findById(learningEventId)).thenReturn(Optional.of(learningEvent));

        worker.onLearningEventRecorded(new LearningEventRecordedEvent(learningEventId, correlationId, userId, null, LearningEventType.APPLICATION_REJECTED));

        verify(engine).analyze(learningEvent);
        var captor = org.mockito.ArgumentCaptor.forClass(FailurePatternsUpdatedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(userId, captor.getValue().userId());
    }

    @Test
    void engineFailureIsIsolatedToDeadLetter() {
        LearningEvent learningEvent = LearningEvent.builder().id(learningEventId).userId(userId).build();
        when(engine.isEnabled()).thenReturn(true);
        when(events.findById(learningEventId)).thenReturn(Optional.of(learningEvent));
        doThrow(new RuntimeException("boom")).when(engine).analyze(any());

        worker.onLearningEventRecorded(new LearningEventRecordedEvent(learningEventId, correlationId, userId, null, LearningEventType.APPLICATION_REJECTED));

        verify(deadLetters).record(eq(correlationId), eq("LEARNING"), eq("FAILURE_PATTERN"), any(), any());
        assertEquals(1, metrics.failures("FAILURE_PATTERN"));
    }

    @Test
    void missingLearningEventIsANoOp() {
        when(engine.isEnabled()).thenReturn(true);
        when(events.findById(learningEventId)).thenReturn(Optional.empty());
        worker.onLearningEventRecorded(new LearningEventRecordedEvent(learningEventId, correlationId, userId, null, LearningEventType.APPLICATION_REJECTED));
        verify(engine, never()).analyze(any());
    }
}
