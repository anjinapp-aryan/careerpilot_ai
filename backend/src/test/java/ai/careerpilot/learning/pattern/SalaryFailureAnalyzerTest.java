package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SalaryFailureAnalyzerTest {
    private final SalaryFailureAnalyzer analyzer = new SalaryFailureAnalyzer();

    @Test
    void dimensionIsSalary() {
        assertEquals(FailurePattern.DIM_SALARY, analyzer.dimension());
    }

    @Test
    void extractsSalaryBandKey() {
        var event = LearningEvent.builder().salaryBand("OVER_200K").build();
        assertEquals(java.util.List.of("OVER_200K"), analyzer.extractKeys(event));
    }

    @Test
    void matchesExactBand() {
        var event = LearningEvent.builder().salaryBand("OVER_200K").build();
        assertTrue(analyzer.matches(event, "OVER_200K"));
        assertFalse(analyzer.matches(event, "UNDER_60K"));
    }
}
