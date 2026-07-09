package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IndustrySuccessAnalyzerTest {
    private final IndustrySuccessAnalyzer analyzer = new IndustrySuccessAnalyzer();

    @Test
    void dimensionIsIndustry() {
        assertEquals(SuccessPattern.DIM_INDUSTRY, analyzer.dimension());
    }

    @Test
    void extractsIndustryKey() {
        var event = LearningEvent.builder().industry("TECH").build();
        assertEquals(java.util.List.of("TECH"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().industry("TECH").build();
        assertTrue(analyzer.matches(event, "tech"));
    }
}
