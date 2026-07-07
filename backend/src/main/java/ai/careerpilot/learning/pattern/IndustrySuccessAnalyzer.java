package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndustrySuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_INDUSTRY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getIndustry() == null || event.getIndustry().isBlank() ? List.of() : List.of(event.getIndustry());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getIndustry());
    }
}
