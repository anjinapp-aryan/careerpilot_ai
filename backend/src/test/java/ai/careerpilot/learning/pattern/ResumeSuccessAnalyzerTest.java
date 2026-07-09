package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResumeSuccessAnalyzerTest {
    private final ResumeSuccessAnalyzer analyzer = new ResumeSuccessAnalyzer();

    @Test
    void dimensionIsResume() {
        assertEquals(SuccessPattern.DIM_RESUME, analyzer.dimension());
    }

    @Test
    void extractsResumeVersionKey() {
        var event = LearningEvent.builder().resumeVersion("v1.7").build();
        assertEquals(java.util.List.of("v1.7"), analyzer.extractKeys(event));
    }

    @Test
    void nullResumeVersionExtractsNoKeys() {
        assertTrue(analyzer.extractKeys(LearningEvent.builder().build()).isEmpty());
    }

    @Test
    void matchesExactVersion() {
        var event = LearningEvent.builder().resumeVersion("v1.7").build();
        assertTrue(analyzer.matches(event, "v1.7"));
        assertFalse(analyzer.matches(event, "v1.8"));
    }
}
