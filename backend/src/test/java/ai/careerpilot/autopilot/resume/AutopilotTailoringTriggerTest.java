package ai.careerpilot.autopilot.resume;

import ai.careerpilot.autopilot.resume.ResumeSelectionEngine.ResumeSelection;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AutopilotTailoringTriggerTest {

    private ResumeSelectionEngine selectionEngine;
    private ApplicationEventPublisher publisher;
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        selectionEngine = mock(ResumeSelectionEngine.class);
        publisher = mock(ApplicationEventPublisher.class);
    }

    private AutopilotTailoringTrigger trigger(boolean enabled) {
        return new AutopilotTailoringTrigger(selectionEngine, publisher, enabled);
    }

    private void stubSelection(SelectionOutcome outcome) {
        when(selectionEngine.select(userId, jobId))
                .thenReturn(Optional.of(new ResumeSelection(outcome, null, null, "r")));
    }

    @Test
    void disabledDoesNothing() {
        assertEquals(TailoringTriggerOutcome.NOT_TRIGGERED, trigger(false).triggerIfNeeded(userId, orgId, jobId));
        verifyNoInteractions(selectionEngine, publisher);
    }

    @Test
    void selectionEngineDisabledReturnsNotTriggered() {
        when(selectionEngine.select(userId, jobId)).thenReturn(Optional.empty());
        assertEquals(TailoringTriggerOutcome.NOT_TRIGGERED, trigger(true).triggerIfNeeded(userId, orgId, jobId));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void alreadySelectedDoesNotTrigger() {
        stubSelection(SelectionOutcome.SELECTED);
        assertEquals(TailoringTriggerOutcome.ALREADY_READY, trigger(true).triggerIfNeeded(userId, orgId, jobId));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void noBaseResumeDoesNotTrigger() {
        stubSelection(SelectionOutcome.NO_BASE_RESUME);
        assertEquals(TailoringTriggerOutcome.NO_BASE_RESUME, trigger(true).triggerIfNeeded(userId, orgId, jobId));
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void needsTailoringPublishesRecommendationApprovedEvent() {
        stubSelection(SelectionOutcome.NEEDS_TAILORING);
        assertEquals(TailoringTriggerOutcome.TAILORING_TRIGGERED, trigger(true).triggerIfNeeded(userId, orgId, jobId));
        var captor = org.mockito.ArgumentCaptor.forClass(RecommendationApprovedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        assertEquals(userId, captor.getValue().userId());
        assertEquals(jobId, captor.getValue().jobId());
        assertEquals(orgId, captor.getValue().orgId());
    }

    @Test
    void selectionThrowingIsIsolated() {
        when(selectionEngine.select(userId, jobId)).thenThrow(new RuntimeException("boom"));
        assertEquals(TailoringTriggerOutcome.NOT_TRIGGERED, trigger(true).triggerIfNeeded(userId, orgId, jobId));
    }
}
