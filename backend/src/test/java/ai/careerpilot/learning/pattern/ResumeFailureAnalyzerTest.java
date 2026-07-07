package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResumeFailureAnalyzerTest {
    private final ResumeFailureAnalyzer analyzer = new ResumeFailureAnalyzer();

    @Test
    void dimensionIsResume() {
        assertEquals(FailurePattern.DIM_RESUME, analyzer.dimension());
    }

    @Test
    void extractsResumeVersionKey() {
        var event = LearningEvent.builder().resumeVersion("v1.2").build();
        assertEquals(java.util.List.of("v1.2"), analyzer.extractKeys(event));
    }

    @Test
    void matchesExactVersion() {
        var event = LearningEvent.builder().resumeVersion("v1.2").build();
        assertTrue(analyzer.matches(event, "v1.2"));
        assertFalse(analyzer.matches(event, "v1.3"));
    }
}
