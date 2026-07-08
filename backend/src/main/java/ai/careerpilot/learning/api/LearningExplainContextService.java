package ai.careerpilot.learning.api;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.career.CareerLearningFacade;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.RecommendationWeightRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.5 integration — Part 11 (AI Copilot). Assembles a read-only snapshot of a user's Phase 6
 * learning state for the Copilot's "Explain Learning" skill, so the assistant can answer questions
 * like "why did this job get boosted?" grounded in real backend data instead of hallucinating.
 * Gated by {@code learning.engine.enabled}; every field is empty/absent when disabled or when the
 * user has no learning history yet.
 */
@Service
public class LearningExplainContextService {

    private static final int TOP_N = 5;

    private final LearningEventRepository events;
    private final SuccessPatternRepository successPatterns;
    private final FailurePatternRepository failurePatterns;
    private final RecommendationWeightRepository weights;
    private final AdaptiveResumeEngine resumeEngine;
    private final CareerLearningFacade careerLearning;
    private final boolean enabled;

    public LearningExplainContextService(LearningEventRepository events,
                                         SuccessPatternRepository successPatterns,
                                         FailurePatternRepository failurePatterns,
                                         RecommendationWeightRepository weights,
                                         AdaptiveResumeEngine resumeEngine,
                                         CareerLearningFacade careerLearning,
                                         @Value("${learning.engine.enabled:false}") boolean enabled) {
        this.events = events;
        this.successPatterns = successPatterns;
        this.failurePatterns = failurePatterns;
        this.weights = weights;
        this.resumeEngine = resumeEngine;
        this.careerLearning = careerLearning;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public LearningExplainContext get(UUID userId) {
        if (!enabled) return LearningExplainContext.empty();
        long totalEvents = events.findByUserIdOrderByCreatedAtDesc(userId).size();
        List<SuccessPattern> topSuccess = successPatterns.findByUserIdOrderBySuccessRateDesc(userId)
                .stream().limit(TOP_N).toList();
        List<FailurePattern> topFailure = failurePatterns.findByUserIdOrderByFailureRateDesc(userId)
                .stream().limit(TOP_N).toList();
        List<RecommendationWeight> topWeights = weights.findByUserId(userId).stream()
                .sorted(Comparator.comparingInt(RecommendationWeight::getBoost).reversed())
                .limit(TOP_N).toList();
        ResumeLearning bestResume = resumeEngine.bestVersion(userId).orElse(null);
        CareerStrategy strategy = careerLearning.strategy(userId).orElse(null);
        return new LearningExplainContext(totalEvents, topSuccess, topFailure, topWeights, bestResume, strategy);
    }

    /** Read-only snapshot of a user's Phase 6 learning state. */
    public record LearningExplainContext(long totalEvents, List<SuccessPattern> topSuccessPatterns,
                                         List<FailurePattern> topFailurePatterns,
                                         List<RecommendationWeight> topRecommendationWeights,
                                         ResumeLearning bestResumeVersion, CareerStrategy careerStrategy) {
        static LearningExplainContext empty() {
            return new LearningExplainContext(0, List.of(), List.of(), List.of(), null, null);
        }
    }
}
