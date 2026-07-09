package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocationSuccessAnalyzerTest {
    private final LocationSuccessAnalyzer analyzer = new LocationSuccessAnalyzer();

    @Test
    void dimensionIsLocation() {
        assertEquals(SuccessPattern.DIM_LOCATION, analyzer.dimension());
    }

    @Test
    void extractsCountryKey() {
        var event = LearningEvent.builder().country("India").build();
        assertEquals(java.util.List.of("India"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().country("India").build();
        assertTrue(analyzer.matches(event, "india"));
    }
}
