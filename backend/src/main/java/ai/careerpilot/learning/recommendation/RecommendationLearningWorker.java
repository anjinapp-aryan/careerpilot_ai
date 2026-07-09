package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.learning.event.FailurePatternsUpdatedEvent;
import ai.careerpilot.learning.event.SuccessPatternsUpdatedEvent;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Phase 6.4 — recomputes recommendation weights whenever either pattern engine updates for a user.
 * Listens to both {@link SuccessPatternsUpdatedEvent} and {@link FailurePatternsUpdatedEvent};
 * {@link RecommendationLearningService#recompute} is a full idempotent upsert, so firing twice
 * (once per pattern event) is harmless.
 */
@Component
public class RecommendationLearningWorker {

    private static final Logger log = LoggerFactory.getLogger(RecommendationLearningWorker.class);

    private final RecommendationLearningService learningService;
    private final LearningMetricsLogRepository metricsLog;
    private final LearningMetrics metrics;
    private final WorkflowDeadLetterService deadLetters;
    private final boolean enabled;

    public RecommendationLearningWorker(RecommendationLearningService learningService,
                                        LearningMetricsLogRepository metricsLog, LearningMetrics metrics,
                                        WorkflowDeadLetterService deadLetters,
                                        @Value("${learning.adaptive-recommendation.enabled:false}") boolean enabled) {
        this.learningService = learningService;
        this.metricsLog = metricsLog;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.enabled = enabled;
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSuccessPatternsUpdated(SuccessPatternsUpdatedEvent event) {
        recompute(event.correlationId(), event.userId());
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFailurePatternsUpdated(FailurePatternsUpdatedEvent event) {
        recompute(event.correlationId(), event.userId());
    }

    private void recompute(UUID correlationId, UUID userId) {
        if (!enabled) return;
        long start = System.currentTimeMillis();
        try {
            learningService.recompute(userId);
            metrics.recordSuccess(LearningMetricsLog.STAGE_RECOMMENDATION_LEARNING);
            metricsLog.save(LearningMetricsLog.builder()
                    .stage(LearningMetricsLog.STAGE_RECOMMENDATION_LEARNING)
                    .status(LearningMetricsLog.STATUS_SUCCESS)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build());
        } catch (Exception e) {
            metrics.recordFailure(LearningMetricsLog.STAGE_RECOMMENDATION_LEARNING);
            log.warn("Recommendation learning failed user={}: {}", userId, e.toString());
            deadLetters.record(correlationId, "LEARNING", "RECOMMENDATION_LEARNING", "userId=" + userId, e);
        }
    }
}
