package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.LearningEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillFailureAnalyzerTest {
    private final SkillFailureAnalyzer analyzer = new SkillFailureAnalyzer();

    @Test
    void dimensionIsSkill() {
        assertEquals(FailurePattern.DIM_SKILL, analyzer.dimension());
    }

    @Test
    void extractsMultipleSkillKeys() {
        var event = LearningEvent.builder().skills("COBOL, Fortran").build();
        assertEquals(java.util.List.of("COBOL", "Fortran"), analyzer.extractKeys(event));
    }

    @Test
    void matchesAnySkillInList() {
        var event = LearningEvent.builder().skills("COBOL,Fortran").build();
        assertTrue(analyzer.matches(event, "fortran"));
        assertFalse(analyzer.matches(event, "Java"));
    }
}
