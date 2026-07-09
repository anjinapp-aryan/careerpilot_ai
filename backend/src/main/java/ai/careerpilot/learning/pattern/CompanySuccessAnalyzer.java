package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompanySuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_COMPANY; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getCompany() == null || event.getCompany().isBlank() ? List.of() : List.of(event.getCompany());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getCompany());
    }
}
