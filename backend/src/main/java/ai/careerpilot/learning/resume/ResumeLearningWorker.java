package ai.careerpilot.learning.resume;

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

/** Phase 6.5 — recomputes resume learning whenever either pattern engine updates for a user. */
@Component
public class ResumeLearningWorker {

    private static final Logger log = LoggerFactory.getLogger(ResumeLearningWorker.class);

    private final ResumeLearningService learningService;
    private final LearningMetricsLogRepository metricsLog;
    private final LearningMetrics metrics;
    private final WorkflowDeadLetterService deadLetters;
    private final boolean enabled;

    public ResumeLearningWorker(ResumeLearningService learningService, LearningMetricsLogRepository metricsLog,
                                LearningMetrics metrics, WorkflowDeadLetterService deadLetters,
                                @Value("${learning.adaptive-resume.enabled:false}") boolean enabled) {
        this.learningService = learningService;
        this.metricsLog = metricsLog;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.enabled = enabled;
    }

    @Async(LearningExecutorsConfig.RESUME_LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSuccessPatternsUpdated(SuccessPatternsUpdatedEvent event) {
        recompute(event.correlationId(), event.userId());
    }

    @Async(LearningExecutorsConfig.RESUME_LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onFailurePatternsUpdated(FailurePatternsUpdatedEvent event) {
        recompute(event.correlationId(), event.userId());
    }

    private void recompute(UUID correlationId, UUID userId) {
        if (!enabled) return;
        long start = System.currentTimeMillis();
        try {
            learningService.recompute(userId);
            metrics.recordSuccess(LearningMetricsLog.STAGE_RESUME_LEARNING);
            metricsLog.save(LearningMetricsLog.builder()
                    .stage(LearningMetricsLog.STAGE_RESUME_LEARNING)
                    .status(LearningMetricsLog.STATUS_SUCCESS)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build());
        } catch (Exception e) {
            metrics.recordFailure(LearningMetricsLog.STAGE_RESUME_LEARNING);
            log.warn("Resume learning failed user={}: {}", userId, e.toString());
            deadLetters.record(correlationId, "LEARNING", "RESUME_LEARNING", "userId=" + userId, e);
        }
    }
}
