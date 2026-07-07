package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.learning.LearningMetrics;
import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.learning.event.FailurePatternsUpdatedEvent;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
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

/** Phase 6.3 — reacts to every captured learning event by recomputing failure patterns for the user. */
@Component
public class FailurePatternWorker {

    private static final Logger log = LoggerFactory.getLogger(FailurePatternWorker.class);

    private final FailurePatternEngine engine;
    private final LearningEventRepository events;
    private final LearningMetricsLogRepository metricsLog;
    private final LearningMetrics metrics;
    private final WorkflowDeadLetterService deadLetters;
    private final ApplicationEventPublisher publisher;

    public FailurePatternWorker(FailurePatternEngine engine, LearningEventRepository events,
                                LearningMetricsLogRepository metricsLog, LearningMetrics metrics,
                                WorkflowDeadLetterService deadLetters, ApplicationEventPublisher publisher) {
        this.engine = engine;
        this.events = events;
        this.metricsLog = metricsLog;
        this.metrics = metrics;
        this.deadLetters = deadLetters;
        this.publisher = publisher;
    }

    @Async(LearningExecutorsConfig.FAILURE_PATTERN_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEventRecorded(LearningEventRecordedEvent event) {
        if (!engine.isEnabled()) return;
        long start = System.currentTimeMillis();
        try {
            LearningEvent learningEvent = events.findById(event.learningEventId()).orElse(null);
            if (learningEvent == null) return;
            engine.analyze(learningEvent);
            metrics.recordSuccess(LearningMetricsLog.STAGE_FAILURE_PATTERN);
            metricsLog.save(LearningMetricsLog.builder()
                    .learningEventId(event.learningEventId())
                    .stage(LearningMetricsLog.STAGE_FAILURE_PATTERN)
                    .status(LearningMetricsLog.STATUS_SUCCESS)
                    .latencyMs(System.currentTimeMillis() - start)
                    .build());
            publisher.publishEvent(new FailurePatternsUpdatedEvent(event.correlationId(), event.userId()));
        } catch (Exception e) {
            metrics.recordFailure(LearningMetricsLog.STAGE_FAILURE_PATTERN);
            log.warn("Failure pattern analysis failed user={}: {}", event.userId(), e.toString());
            deadLetters.record(event.correlationId(), "LEARNING", "FAILURE_PATTERN", "userId=" + event.userId(), e);
        }
    }
}
