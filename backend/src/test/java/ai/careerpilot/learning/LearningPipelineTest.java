package ai.careerpilot.learning;

import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningPipelineTest {

    private LearningEventService eventService;
    private LearningMetricsLogRepository metricsLog;
    private WorkflowDeadLetterService deadLetters;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        eventService = mock(LearningEventService.class);
        metricsLog = mock(LearningMetricsLogRepository.class);
        deadLetters = mock(WorkflowDeadLetterService.class);
    }

    @Test
    void disabledPipelineNeverRecords() {
        LearningPipeline pipeline = new LearningPipeline(eventService, metricsLog, deadLetters, false);
        pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null);
        verifyNoInteractions(eventService);
        assertFalse(pipeline.isEnabled());
    }

    @Test
    void nullUserIdIsANoOpEvenWhenEnabled() {
        LearningPipeline pipeline = new LearningPipeline(eventService, metricsLog, deadLetters, true);
        pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, null, null, null, null);
        verifyNoInteractions(eventService);
    }

    @Test
    void enabledPipelineDelegatesToEventService() {
        LearningPipeline pipeline = new LearningPipeline(eventService, metricsLog, deadLetters, true);
        pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null);
        verify(eventService).record(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null);
    }

    @Test
    void captureFailureIsIsolatedToDeadLetterNeverThrows() {
        doThrow(new RuntimeException("db down")).when(eventService)
                .record(any(), any(), any(), any(), any(), any());
        LearningPipeline pipeline = new LearningPipeline(eventService, metricsLog, deadLetters, true);

        assertDoesNotThrow(() -> pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null));
        verify(deadLetters).record(isNull(), eq("LEARNING"), eq("EVENT_CAPTURE"), any(), any());
    }

    @Test
    void captureFailureStillLogsMetricsAudit() {
        doThrow(new RuntimeException("db down")).when(eventService)
                .record(any(), any(), any(), any(), any(), any());
        LearningPipeline pipeline = new LearningPipeline(eventService, metricsLog, deadLetters, true);
        pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, userId, null, null, null);
        verify(metricsLog).save(argThat(log -> LearningMetricsLog.STATUS_FAILED.equals(log.getStatus())));
    }
}
