package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ResumeSuccessAnalyzer implements SuccessDimensionAnalyzer {
    @Override public String dimension() { return SuccessPattern.DIM_RESUME; }

    @Override
    public List<String> extractKeys(LearningEvent event) {
        return event.getResumeVersion() == null || event.getResumeVersion().isBlank() ? List.of() : List.of(event.getResumeVersion());
    }

    @Override
    public boolean matches(LearningEvent event, String key) {
        return key.equalsIgnoreCase(event.getResumeVersion());
    }
}
