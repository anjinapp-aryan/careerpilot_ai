package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RoleSuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_ROLE; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getRoleFamily() == null || event.getRoleFamily().isBlank() ? List.of() : List.of(event.getRoleFamily());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getRoleFamily());
    }
}
