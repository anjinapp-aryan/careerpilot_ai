package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndustryFailureAnalyzerTest {
    private final IndustryFailureAnalyzer analyzer = new IndustryFailureAnalyzer();

    @Test
    void dimensionIsIndustry() {
        assertEquals(FailurePattern.DIM_INDUSTRY, analyzer.dimension());
    }

    @Test
    void extractsIndustryKey() {
        var event = LearningEvent.builder().industry("FINANCE").build();
        assertEquals(java.util.List.of("FINANCE"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().industry("FINANCE").build();
        assertTrue(analyzer.matches(event, "finance"));
    }
}
