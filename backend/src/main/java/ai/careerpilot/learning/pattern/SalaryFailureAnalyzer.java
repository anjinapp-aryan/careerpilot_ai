package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.FailurePattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SalaryFailureAnalyzer implements FailureDimensionAnalyzer {
    @Override public String dimension() { return FailurePattern.DIM_SALARY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getSalaryBand() == null || event.getSalaryBand().isBlank() ? List.of() : List.of(event.getSalaryBand());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getSalaryBand());
    }
}
