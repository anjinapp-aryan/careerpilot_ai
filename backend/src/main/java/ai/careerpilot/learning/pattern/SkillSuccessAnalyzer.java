package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class SkillSuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_SKILL; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return csv(event.getSkills());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return csv(event.getSkills()).stream().anyMatch(s -> s.equalsIgnoreCase(key));
    }

    static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
