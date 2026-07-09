package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.LearningEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FailurePatternEngineTest {

    private LearningEventRepository events;
    private FailurePatternRepository patterns;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        patterns = mock(FailurePatternRepository.class);
        when(patterns.findByUserIdAndDimensionAndDimensionKey(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void disabledEngineNeverPersists() {
        FailurePatternEngine engine = new FailurePatternEngine(List.of(new LocationFailureAnalyzer()), events, patterns, false);
        engine.analyze(LearningEvent.builder().userId(userId).country("Germany").build());
        verify(patterns, never()).save(any());
    }

    @Test
    void enabledEngineUpsertsPatternForTriggeringDimension() {
        LearningEvent trigger = LearningEvent.builder().userId(userId).country("Germany")
                .eventType(LearningEventType.APPLICATION_SUBMITTED.name()).build();
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(trigger));

        FailurePatternEngine engine = new FailurePatternEngine(List.of(new LocationFailureAnalyzer()), events, patterns, true);
        engine.analyze(trigger);

        var captor = org.mockito.ArgumentCaptor.forClass(FailurePattern.class);
        verify(patterns).save(captor.capture());
        assertEquals("Germany", captor.getValue().getDimensionKey());
        assertEquals(1, captor.getValue().getApplications());
        assertEquals(-30, captor.getValue().getRecommendedPenalty());
    }

    @Test
    void eventWithNoKeyPersistsNothing() {
        FailurePatternEngine engine = new FailurePatternEngine(List.of(new LocationFailureAnalyzer()), events, patterns, true);
        engine.analyze(LearningEvent.builder().userId(userId).build());
        verify(patterns, never()).save(any());
    }
}
