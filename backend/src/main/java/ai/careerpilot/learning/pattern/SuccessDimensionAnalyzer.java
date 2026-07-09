package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;

import java.util.List;

/** Phase 6.2 — one dimension's key-extraction/matching logic; the math itself lives in {@link SuccessPatternEngine}. */
public interface SuccessDimensionAnalyzer {
    String dimension();
    /** The dimension key(s) this triggering event should recompute a pattern for (empty if none apply). */
    List<String> extractKeys(LearningEvent triggeringEvent);
    /** Whether a historical event belongs to the given dimension key's cohort. */
    boolean matches(LearningEvent event, String key);
}
