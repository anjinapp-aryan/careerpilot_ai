package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoleSuccessAnalyzerTest {
    private final RoleSuccessAnalyzer analyzer = new RoleSuccessAnalyzer();

    @Test
    void dimensionIsRole() {
        assertEquals(SuccessPattern.DIM_ROLE, analyzer.dimension());
    }

    @Test
    void extractsRoleFamilyKey() {
        var event = LearningEvent.builder().roleFamily("TECH").build();
        assertEquals(java.util.List.of("TECH"), analyzer.extractKeys(event));
    }

    @Test
    void nullRoleFamilyExtractsNoKeys() {
        assertTrue(analyzer.extractKeys(LearningEvent.builder().build()).isEmpty());
    }

    @Test
    void matchesIsCaseInsensitive() {
        var event = LearningEvent.builder().roleFamily("TECH").build();
        assertTrue(analyzer.matches(event, "tech"));
    }
}
