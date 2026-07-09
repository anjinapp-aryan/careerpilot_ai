package ai.careerpilot.execution.tracking;

import ai.careerpilot.domain.ApplicationTracking;
import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2E.7 — reacts to an {@link ApplicationSubmittedEvent} and opens the tracking timeline with a
 * SUBMITTED entry, on the dedicated bounded {@code trackingExecutor}. Because nothing in the 2E build
 * ever submits, this event is never published and the worker never fires. Async, after-commit,
 * failure-isolated; gated by {@code application.tracking.trigger.enabled}.
 */
@Component
public class TrackingWorker {

    private static final Logger log = LoggerFactory.getLogger(TrackingWorker.class);

    private final ApplicationTrackingService tracking;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public TrackingWorker(ApplicationTrackingService tracking,
                          @Qualifier(ExecutionExecutorsConfig.TRACKING_EXECUTOR) ThreadPoolTaskExecutor executor,
                          @Value("${application.tracking.trigger.enabled:false}") boolean triggerEnabled) {
        this.tracking = tracking;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(ExecutionExecutorsConfig.TRACKING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        if (!triggerEnabled || !tracking.isEnabled()) return;
        try {
            executor.execute(() -> tracking.record(event.executionId(), event.userId(), event.jobId(),
                    event.applicationId(), ApplicationTracking.STATUS_SUBMITTED,
                    "submitted via " + event.provider()));
        } catch (Exception e) {
            log.warn("Tracking worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
