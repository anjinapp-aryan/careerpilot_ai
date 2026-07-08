package ai.careerpilot.learning;

import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LearningTimelineBridgeTest {

    private TimelineService timeline;
    private WorkflowDeadLetterService deadLetters;
    private final UUID learningEventId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        timeline = mock(TimelineService.class);
        deadLetters = mock(WorkflowDeadLetterService.class);
    }

    @Test
    void disabledNeverAppends() {
        LearningTimelineBridge bridge = new LearningTimelineBridge(timeline, deadLetters, false);
        bridge.onLearningEventRecorded(new LearningEventRecordedEvent(
                learningEventId, correlationId, userId, jobId, LearningEventType.APPLICATION_SUBMITTED));
        verifyNoInteractions(timeline);
    }

    @Test
    void noJobIdIsSkipped() {
        LearningTimelineBridge bridge = new LearningTimelineBridge(timeline, deadLetters, true);
        bridge.onLearningEventRecorded(new LearningEventRecordedEvent(
                learningEventId, correlationId, userId, null, LearningEventType.WORKFLOW_COMPLETED));
        verifyNoInteractions(timeline);
    }

    @Test
    void enabledWithJobIdAppendsLearningStarted() {
        LearningTimelineBridge bridge = new LearningTimelineBridge(timeline, deadLetters, true);
        bridge.onLearningEventRecorded(new LearningEventRecordedEvent(
                learningEventId, correlationId, userId, jobId, LearningEventType.APPLICATION_SUBMITTED));
        verify(timeline).append(eq(userId), eq(jobId), eq("LEARNING_STARTED"), eq("LEARNING"), isNull(), contains("APPLICATION_SUBMITTED"));
    }

    @Test
    void timelineFailureIsIsolatedToDeadLetter() {
        doThrow(new RuntimeException("boom")).when(timeline).append(any(), any(), any(), any(), any(), any());
        LearningTimelineBridge bridge = new LearningTimelineBridge(timeline, deadLetters, true);
        bridge.onLearningEventRecorded(new LearningEventRecordedEvent(
                learningEventId, correlationId, userId, jobId, LearningEventType.APPLICATION_SUBMITTED));
        verify(deadLetters).record(eq(correlationId), eq("LEARNING"), eq("TIMELINE"), any(), any(RuntimeException.class));
    }
}
