package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SalarySuccessAnalyzerTest {
    private final SalarySuccessAnalyzer analyzer = new SalarySuccessAnalyzer();

    @Test
    void dimensionIsSalary() {
        assertEquals(SuccessPattern.DIM_SALARY, analyzer.dimension());
    }

    @Test
    void extractsSalaryBandKey() {
        var event = LearningEvent.builder().salaryBand("100K_150K").build();
        assertEquals(java.util.List.of("100K_150K"), analyzer.extractKeys(event));
    }

    @Test
    void matchesExactBand() {
        var event = LearningEvent.builder().salaryBand("100K_150K").build();
        assertTrue(analyzer.matches(event, "100K_150K"));
        assertFalse(analyzer.matches(event, "60K_100K"));
    }
}
