package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.FailurePattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompanyFailureAnalyzer implements FailureDimensionAnalyzer {
    @Override public String dimension() { return FailurePattern.DIM_COMPANY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getCompany() == null || event.getCompany().isBlank() ? List.of() : List.of(event.getCompany());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getCompany());
    }
}
