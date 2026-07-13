package ai.careerpilot.story.worker;

import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.story.StarStoryEngine;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StoryExtractionWorkerTest {

    @Test
    void mapsKnownOutcomesToAStoryType() {
        assertEquals(StoryType.DELIVERY, StoryExtractionWorker.mapEventType(LearningEventType.APPLICATION_SUBMITTED));
        assertEquals(StoryType.COMMUNICATION, StoryExtractionWorker.mapEventType(LearningEventType.INTERVIEW_COMPLETED));
        assertEquals(StoryType.SUCCESS, StoryExtractionWorker.mapEventType(LearningEventType.OFFER_RECEIVED));
        assertEquals(StoryType.CAREER_GROWTH, StoryExtractionWorker.mapEventType(LearningEventType.RECOMMENDATION_APPROVED));
    }

    @Test
    void unmappedOutcomesReturnNull() {
        assertNull(StoryExtractionWorker.mapEventType(LearningEventType.APPLICATION_REJECTED));
        assertNull(StoryExtractionWorker.mapEventType(null));
    }

    @Test
    void disabledWorkerNeverCallsEngine() {
        StarStoryEngine engine = mock(StarStoryEngine.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        StoryExtractionWorker worker = new StoryExtractionWorker(engine, executor, false);
        assertFalse(worker.isEnabled());

        worker.onLearningEventRecorded(new LearningEventRecordedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LearningEventType.APPLICATION_SUBMITTED));
        verifyNoInteractions(executor);
        verifyNoInteractions(engine);
    }

    @Test
    void enabledWorkerRequiresEngineAlsoEnabled() {
        StarStoryEngine engine = mock(StarStoryEngine.class);
        when(engine.isEnabled()).thenReturn(false);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        StoryExtractionWorker worker = new StoryExtractionWorker(engine, executor, true);
        assertFalse(worker.isEnabled());
    }

    @Test
    void draftInvokesEngineGenerateForMappedEventType() {
        StarStoryEngine engine = mock(StarStoryEngine.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        StoryExtractionWorker worker = new StoryExtractionWorker(engine, executor, true);
        UUID userId = UUID.randomUUID();

        worker.draft(new LearningEventRecordedEvent(UUID.randomUUID(), UUID.randomUUID(), userId,
                UUID.randomUUID(), LearningEventType.OFFER_RECEIVED));
        verify(engine).generate(eq(userId), eq(StoryType.SUCCESS), any(), any());
    }

    @Test
    void draftSkipsUnmappedEventTypesWithoutThrowing() {
        StarStoryEngine engine = mock(StarStoryEngine.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        StoryExtractionWorker worker = new StoryExtractionWorker(engine, executor, true);

        assertDoesNotThrow(() -> worker.draft(new LearningEventRecordedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LearningEventType.APPLICATION_REJECTED)));
        verifyNoInteractions(engine);
    }

    @Test
    void draftIsolatesEngineExceptions() {
        StarStoryEngine engine = mock(StarStoryEngine.class);
        when(engine.generate(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        StoryExtractionWorker worker = new StoryExtractionWorker(engine, executor, true);

        assertDoesNotThrow(() -> worker.draft(new LearningEventRecordedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LearningEventType.OFFER_RECEIVED)));
    }
}
