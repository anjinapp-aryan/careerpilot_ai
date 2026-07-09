package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SuccessPatternEngineTest {

    private LearningEventRepository events;
    private SuccessPatternRepository patterns;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        patterns = mock(SuccessPatternRepository.class);
        when(patterns.findByUserIdAndDimensionAndDimensionKey(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void disabledEngineNeverPersists() {
        SuccessPatternEngine engine = new SuccessPatternEngine(List.of(new CompanySuccessAnalyzer()), events, patterns, false);
        engine.analyze(LearningEvent.builder().userId(userId).company("Acme").build());
        verify(patterns, never()).save(any());
        assertFalse(engine.isEnabled());
    }

    @Test
    void enabledEngineUpsertsPatternForTriggeringDimension() {
        LearningEvent trigger = LearningEvent.builder().userId(userId).company("Acme")
                .eventType(LearningEventType.APPLICATION_SUBMITTED.name()).build();
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(trigger));

        SuccessPatternEngine engine = new SuccessPatternEngine(List.of(new CompanySuccessAnalyzer()), events, patterns, true);
        engine.analyze(trigger);

        var captor = org.mockito.ArgumentCaptor.forClass(SuccessPattern.class);
        verify(patterns).save(captor.capture());
        assertEquals("Acme", captor.getValue().getDimensionKey());
        assertEquals(1, captor.getValue().getApplications());
    }

    @Test
    void eventWithNoKeyForAnalyzerPersistsNothing() {
        LearningEvent trigger = LearningEvent.builder().userId(userId).build(); // no company
        SuccessPatternEngine engine = new SuccessPatternEngine(List.of(new CompanySuccessAnalyzer()), events, patterns, true);
        engine.analyze(trigger);
        verify(patterns, never()).save(any());
    }

    @Test
    void updatesExistingRowInPlace() {
        LearningEvent trigger = LearningEvent.builder().userId(userId).company("Acme")
                .eventType(LearningEventType.OFFER_RECEIVED.name()).build();
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(trigger));
        SuccessPattern existing = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_COMPANY).dimensionKey("Acme").build();
        when(patterns.findByUserIdAndDimensionAndDimensionKey(userId, SuccessPattern.DIM_COMPANY, "Acme"))
                .thenReturn(Optional.of(existing));

        SuccessPatternEngine engine = new SuccessPatternEngine(List.of(new CompanySuccessAnalyzer()), events, patterns, true);
        engine.analyze(trigger);

        verify(patterns).save(existing);
        assertEquals(1, existing.getOffers());
    }
}
