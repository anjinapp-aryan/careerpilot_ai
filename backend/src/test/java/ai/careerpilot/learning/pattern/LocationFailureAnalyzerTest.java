package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocationFailureAnalyzerTest {
    private final LocationFailureAnalyzer analyzer = new LocationFailureAnalyzer();

    @Test
    void dimensionIsLocation() {
        assertEquals(FailurePattern.DIM_LOCATION, analyzer.dimension());
    }

    @Test
    void extractsCountryKey() {
        var event = LearningEvent.builder().country("Germany").build();
        assertEquals(java.util.List.of("Germany"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().country("Germany").build();
        assertTrue(analyzer.matches(event, "germany"));
    }
}
