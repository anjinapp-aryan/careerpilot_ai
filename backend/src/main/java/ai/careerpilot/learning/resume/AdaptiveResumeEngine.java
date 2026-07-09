package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.repo.ResumeLearningRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.5 — read-only facade over learned resume performance, gated by
 * {@code learning.adaptive-resume.enabled} (env {@code ADAPTIVE_RESUME_ENABLED}). Not wired into
 * {@code ResumeTailoringService}/{@code AtsOptimizationService} in this pass — same reasoning as
 * {@code AdaptiveRecommendationEngine}: additive-only, no regression risk to the live Phase 2D
 * pipeline. {@link #bestVersion} is ready for a future tailoring-strategy hook.
 */
@Component
public class AdaptiveResumeEngine {

    private final ResumeLearningRepository resumeLearning;
    private final boolean enabled;

    public AdaptiveResumeEngine(ResumeLearningRepository resumeLearning,
                                @Value("${learning.adaptive-resume.enabled:false}") boolean enabled) {
        this.resumeLearning = resumeLearning;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The user's current best-performing resume version, or empty when disabled/no data. */
    public Optional<ResumeLearning> bestVersion(UUID userId) {
        if (!enabled) return Optional.empty();
        return resumeLearning.findByUserIdAndBestVersionTrue(userId);
    }
}
