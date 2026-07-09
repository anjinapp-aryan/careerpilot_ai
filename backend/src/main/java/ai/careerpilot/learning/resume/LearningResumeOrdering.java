package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.learning.recommendation.AdaptiveRecommendationEngine;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.5 integration — reorders a skill list (profile skills / job skills fed into the resume
 * tailoring prompt) by the user's learned per-skill recommendation weight, most successful first.
 * Gated by {@code learning.adaptive-resume.enabled} ({@link AdaptiveResumeEngine#isEnabled}); the
 * actual per-skill weight data is read from {@link AdaptiveRecommendationEngine} (the same
 * {@code recommendation_weight} table used for job scoring — skill success is a single learned
 * signal shared across both surfaces, not duplicated).
 *
 * <p>Never invents, drops, or renames a skill — it is a stable sort only, so
 * {@code ResumeTailoringPromptBuilder}'s "never invent skills" guarantee is untouched. When
 * disabled, {@link #orderSkills} returns the input list unchanged (same object), so stock (dark)
 * behavior is identical to before this integration.
 */
@Component
public class LearningResumeOrdering {

    private final AdaptiveResumeEngine resumeEngine;
    private final AdaptiveRecommendationEngine recommendationEngine;

    public LearningResumeOrdering(AdaptiveResumeEngine resumeEngine, AdaptiveRecommendationEngine recommendationEngine) {
        this.resumeEngine = resumeEngine;
        this.recommendationEngine = recommendationEngine;
    }

    public boolean isEnabled() {
        return resumeEngine.isEnabled();
    }

    /** Stable-sorts {@code skills} by learned per-skill boost (descending); unchanged when disabled/empty. */
    public List<String> orderSkills(UUID userId, List<String> skills) {
        if (!isEnabled() || skills == null || skills.size() < 2) return skills;
        return skills.stream()
                .sorted(Comparator.comparingInt(
                        (String s) -> recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SKILL, s))
                        .reversed())
                .toList();
    }
}
