package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanyFailureAnalyzerTest {
    private final CompanyFailureAnalyzer analyzer = new CompanyFailureAnalyzer();

    @Test
    void dimensionIsCompany() {
        assertEquals(FailurePattern.DIM_COMPANY, analyzer.dimension());
    }

    @Test
    void extractsCompanyKey() {
        var event = LearningEvent.builder().company("Globex").build();
        assertEquals(java.util.List.of("Globex"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().company("Globex").build();
        assertTrue(analyzer.matches(event, "globex"));
        assertFalse(analyzer.matches(event, "Other"));
    }
}
