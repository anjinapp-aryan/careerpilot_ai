package ai.careerpilot.service;

import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.repo.RecommendationFeedbackRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2C-4 (Step 6) — records explicit user reactions to recommendations, feeding the
 * {@code candidate_behavior_profile} (2C-5). Two entry points: the {@code POST /feedback} endpoint
 * for standalone reactions, and an automatic capture from {@link RecommendationDecisionService}'s
 * approve/reject/save/archive (so a decision always also leaves a feedback trail).
 *
 * <p>Flag-gated by {@code recommendation.feedback.enabled} (default off): {@link #record} is a no-op
 * (returns empty) when disabled, so the automatic-capture path never writes until explicitly enabled.
 */
@Service
public class RecommendationFeedbackService {

    /** Canonical feedback actions (upper-case). Kept small so behavior-profile aggregation is clean. */
    static final Set<String> ACTIONS = Set.of("APPROVE", "REJECT", "IGNORE", "SAVE", "APPLY_LATER");

    private final RecommendationFeedbackRepository feedback;
    private final boolean enabled;

    public RecommendationFeedbackService(RecommendationFeedbackRepository feedback,
                                         @Value("${recommendation.feedback.enabled:false}") boolean enabled) {
        this.feedback = feedback;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Whether {@code action} is a recognised feedback action (case-insensitive). */
    public static boolean isValidAction(String action) {
        return action != null && ACTIONS.contains(action.trim().toUpperCase());
    }

    /**
     * Append one feedback row. No-op (empty) when disabled — this is what makes the automatic capture
     * from a decision safe to always call. Throws {@link IllegalArgumentException} on an unknown action.
     */
    @Transactional
    public Optional<RecommendationFeedback> record(UUID userId, UUID jobId, String action, String reason) {
        if (!enabled) return Optional.empty();
        String normalized = action == null ? "" : action.trim().toUpperCase();
        if (!ACTIONS.contains(normalized)) {
            throw new IllegalArgumentException("unknown feedback action: " + action);
        }
        return Optional.of(feedback.save(RecommendationFeedback.builder()
                .userId(userId).jobId(jobId).action(normalized).reason(reason)
                .build()));
    }

    public List<RecommendationFeedback> listForUser(UUID userId) {
        return feedback.findByUserIdOrderByCreatedAtDesc(userId);
    }
}
