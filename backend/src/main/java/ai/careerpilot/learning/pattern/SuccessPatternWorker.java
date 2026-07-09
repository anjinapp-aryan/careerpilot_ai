package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.learning.event.SuccessPatternsUpdatedEvent;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Phase 6.2 — reacts to every captured learning event by recomputing success patterns for the user. */
@Component
public class SuccessPatternWorker {

    private static final Logger log = LoggerFactory.getLogger(SuccessPatternWorker.class);

    private final SuccessPatternEngine engine;
    private final LearningEventRepository events;
    private final LearningMetricsLogRepository metricsLog;
    private final LearningMetrics metrics;
    private final WorkflowDeadLetterService deadLetters;
    private final ApplicationEventPublisher publisher;

    public SuccessPatternWorker(SuccessPatternEngine engine, LearningEventRepository events,
                                LearningMetricsLogRepository metricsLog, LearningMetrics metrics,
                                WorkflowDeadLetterService deadLetters, ApplicationEventPublisher publisher) {
        this.engine = engine;
        this.events = events;
        this.metricsLog = metricsLog;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.publisher = publisher;
    }

    @Async(LearningExecutorsConfig.SUCCESS_PATTERN_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEventRecorded(LearningEventRecordedEvent event) {
        if (!engine.isEnabled()) return;
        long start = System.currentTimeMillis();
        try {
            LearningEvent learningEvent = events.findById(event.learningEventId()).orElse(null);
            if (learningEvent == null) return;
            engine.analyze(learningEvent);
            metrics.recordSuccess(LearningMetricsLog.STAGE_SUCCESS_PATTERN);
            metricsLog.save(LearningMetricsLog.builder()
                    .learningEventId(event.learningEventId())
                    .stage(LearningMetricsLog.STAGE_SUCCESS_PATTERN)
                    .status(LearningMetricsLog.STATUS_SUCCESS)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build());
            publisher.publishEvent(new SuccessPatternsUpdatedEvent(event.correlationId(), event.userId()));
        } catch (Exception e) {
            metrics.recordFailure(LearningMetricsLog.STAGE_SUCCESS_PATTERN);
            log.warn("Success pattern analysis failed user={}: {}", event.userId(), e.toString());
            deadLetters.record(event.correlationId(), "LEARNING", "SUCCESS_PATTERN", "userId=" + event.userId(), e);
        }
    }
}
