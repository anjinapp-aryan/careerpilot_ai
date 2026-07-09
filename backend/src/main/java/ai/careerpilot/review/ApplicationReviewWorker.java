package ai.careerpilot.review;

import ai.careerpilot.packageintel.event.ApplicationPackageValidatedEvent;
import ai.careerpilot.review.config.ReviewExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Phase 7.12 — chains the AI Review Pipeline onto the Phase 7.11
 * {@link ApplicationPackageValidatedEvent}, so review runs AFTER package validation and BEFORE the
 * Application Decision, without modifying any prior workflow (a new consumer on a new additive event).
 *
 * <p>Dark by default: gated by BOTH {@code application.review.trigger.enabled} and the pipeline's own
 * {@code application.review.enabled}. Runs on a dedicated bounded executor, reuses the incoming
 * correlation id, and isolates any failure to the {@link WorkflowDeadLetterService} sink instead of
 * propagating.
 */
@Component
public class ApplicationReviewWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationReviewWorker.class);
    private static final String WORKFLOW = "APPLICATION_REVIEW";
    private static final String STAGE = "AI_REVIEW";

    private final ApplicationReviewPipeline pipeline;
    private final ThreadPoolTaskExecutor executor;
    private final WorkflowCorrelationService correlation;
    private final WorkflowDeadLetterService deadLetter;
    private final boolean triggerEnabled;

    public ApplicationReviewWorker(ApplicationReviewPipeline pipeline,
                                   @Qualifier(ReviewExecutorsConfig.APPLICATION_REVIEW_EXECUTOR) ThreadPoolTaskExecutor executor,
                                   WorkflowCorrelationService correlation, WorkflowDeadLetterService deadLetter,
                                   @Value("${application.review.trigger.enabled:false}") boolean triggerEnabled) {
        this.pipeline = pipeline;
        this.executor = executor;
        this.correlation = correlation;
        this.deadLetter = deadLetter;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(ReviewExecutorsConfig.APPLICATION_REVIEW_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationPackageValidated(ApplicationPackageValidatedEvent event) {
        if (!triggerEnabled || !pipeline.isEnabled()) return;
        UUID cid = event.correlationId() != null ? event.correlationId()
                : correlation.start(WORKFLOW, event.userId(), event.jobId(), null);
        try {
            executor.execute(() -> {
                try {
                    pipeline.review(event.applicationPackageId(), cid);
                } catch (Exception e) {
                    deadLetter.record(cid, WORKFLOW, STAGE, event.toString(), e);
                    log.warn("APP_REVIEW worker task failed package={}: {}", event.applicationPackageId(), e.toString());
                }
            });
        } catch (Exception e) {
            deadLetter.record(cid, WORKFLOW, STAGE, event.toString(), e);
            log.warn("APP_REVIEW worker dispatch failed package={}: {}", event.applicationPackageId(), e.toString());
        }
    }
}
