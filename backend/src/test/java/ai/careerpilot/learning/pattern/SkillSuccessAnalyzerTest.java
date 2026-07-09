package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillSuccessAnalyzerTest {
    private final SkillSuccessAnalyzer analyzer = new SkillSuccessAnalyzer();

    @Test
    void dimensionIsSkill() {
        assertEquals(SuccessPattern.DIM_SKILL, analyzer.dimension());
    }

    @Test
    void extractsMultipleSkillKeys() {
        var event = LearningEvent.builder().skills("Java, Spring, AWS").build();
        assertEquals(java.util.List.of("Java", "Spring", "AWS"), analyzer.extractKeys(event));
    }

    @Test
    void blankSkillsExtractsNoKeys() {
        assertTrue(analyzer.extractKeys(LearningEvent.builder().skills("").build()).isEmpty());
    }

    @Test
    void matchesAnySkillInList() {
        var event = LearningEvent.builder().skills("Java,Spring").build();
        assertTrue(analyzer.matches(event, "spring"));
        assertFalse(analyzer.matches(event, "Python"));
    }
}
