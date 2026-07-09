package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.FailurePattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndustryFailureAnalyzer implements FailureDimensionAnalyzer {
    @Override public String dimension() { return FailurePattern.DIM_INDUSTRY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getIndustry() == null || event.getIndustry().isBlank() ? List.of() : List.of(event.getIndustry());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getIndustry());
    }
}
