package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.FailurePattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SkillFailureAnalyzer implements FailureDimensionAnalyzer {
    @Override public String dimension() { return FailurePattern.DIM_SKILL; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return SkillSuccessAnalyzer.csv(event.getSkills());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return SkillSuccessAnalyzer.csv(event.getSkills()).stream().anyMatch(s -> s.equalsIgnoreCase(key));
    }
}
