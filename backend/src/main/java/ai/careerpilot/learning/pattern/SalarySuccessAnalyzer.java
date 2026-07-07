package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SalarySuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_SALARY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getSalaryBand() == null || event.getSalaryBand().isBlank() ? List.of() : List.of(event.getSalaryBand());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getSalaryBand());
    }
}
