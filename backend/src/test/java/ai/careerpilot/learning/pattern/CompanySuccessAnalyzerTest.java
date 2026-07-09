package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompanySuccessAnalyzerTest {
    private final CompanySuccessAnalyzer analyzer = new CompanySuccessAnalyzer();

    @Test
    void dimensionIsCompany() {
        assertEquals(SuccessPattern.DIM_COMPANY, analyzer.dimension());
    }

    @Test
    void extractsCompanyKey() {
        var event = LearningEvent.builder().company("Acme").build();
        assertEquals(java.util.List.of("Acme"), analyzer.extractKeys(event));
    }

    @Test
    void blankCompanyExtractsNoKeys() {
        var event = LearningEvent.builder().company(" ").build();
        assertTrue(analyzer.extractKeys(event).isEmpty());
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().company("Acme").build();
        assertTrue(analyzer.matches(event, "acme"));
        assertFalse(analyzer.matches(event, "Other"));
    }
}
