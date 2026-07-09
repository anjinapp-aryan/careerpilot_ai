package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.FailurePattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocationFailureAnalyzer implements FailureDimensionAnalyzer {
    @Override public String dimension() { return FailurePattern.DIM_LOCATION; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getCountry() == null || event.getCountry().isBlank() ? List.of() : List.of(event.getCountry());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getCountry());
    }
}
