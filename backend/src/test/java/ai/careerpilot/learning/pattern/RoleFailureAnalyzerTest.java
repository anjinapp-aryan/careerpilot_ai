package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleFailureAnalyzerTest {
    private final RoleFailureAnalyzer analyzer = new RoleFailureAnalyzer();

    @Test
    void dimensionIsRole() {
        assertEquals(FailurePattern.DIM_ROLE, analyzer.dimension());
    }

    @Test
    void extractsRoleFamilyKey() {
        var event = LearningEvent.builder().roleFamily("SALES").build();
        assertEquals(java.util.List.of("SALES"), analyzer.extractKeys(event));
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().roleFamily("SALES").build();
        assertTrue(analyzer.matches(event, "sales"));
    }
}
