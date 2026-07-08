package ai.careerpilot.learning;

import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 6.5 integration — Part 4 (Workflow Engine). A third, purely-additive listener alongside
 * {@link LearningEventBridge} that appends a "Learning Started" entry to the existing
 * {@link TimelineService} application timeline whenever a job-scoped learning event is captured, so
 * the Workflow page's existing timeline shows learning activity without any new timeline mechanism.
 *
 * <p>Only {@link LearningEventRecordedEvent} carries a per-job correlation (a {@code jobId}); the
 * downstream success/failure-pattern, recommendation, resume, and career learning stages (Phase
 * 6.2-6.6) operate on user-level dimensions with no job to attach a timeline entry to, so this
 * bridge intentionally does not fabricate "Patterns Updated"/"Recommendation Updated"/etc. timeline
 * rows for them — that per-job granularity doesn't exist in the underlying data model.
 *
 * <p>Gated by {@code learning.engine.enabled}; {@link TimelineService#append} is itself gated by
 * {@code workflow.timeline.enabled} and already a no-op when off, so this bridge is inert unless
 * both flags are on. Never throws — isolated to the shared {@link WorkflowDeadLetterService}.
 */
@Component
public class LearningTimelineBridge {

    private static final Logger log = LoggerFactory.getLogger(LearningTimelineBridge.class);

    private final TimelineService timeline;
    private final WorkflowDeadLetterService deadLetters;
    private final boolean enabled;

    public LearningTimelineBridge(TimelineService timeline, WorkflowDeadLetterService deadLetters,
                                  @Value("${learning.engine.enabled:false}") boolean enabled) {
        this.timeline = timeline;
        this.deadLetters = deadLetters;
        this.enabled = enabled;
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEventRecorded(LearningEventRecordedEvent event) {
        if (!enabled || event.jobId() == null) return;
        try {
            timeline.append(event.userId(), event.jobId(), "LEARNING_STARTED", "LEARNING", null,
                    "Learning captured: " + event.eventType().name());
        } catch (Exception e) {
            log.warn("LEARNING_TIMELINE error user={} job={}: {}", event.userId(), event.jobId(), e.toString());
            deadLetters.record(event.correlationId(), "LEARNING", "TIMELINE", event.toString(), e);
        }
    }
}
