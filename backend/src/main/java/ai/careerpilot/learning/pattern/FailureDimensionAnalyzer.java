package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;

import java.util.List;

/** Phase 6.3 — one dimension's key-extraction/matching logic; the math itself lives in {@link FailurePatternEngine}. */
public interface FailureDimensionAnalyzer {
    String dimension();
    List<String> extractKeys(LearningEvent triggeringEvent);
    boolean matches(LearningEvent event, String key);
}
