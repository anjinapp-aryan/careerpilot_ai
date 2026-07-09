package ai.careerpilot.execution.analytics;

import ai.careerpilot.domain.ExecutionAnalytics;
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

import java.math.BigDecimal;

/**
 * Phase 2E.8 — reacts to an {@link ApplicationSubmittedEvent} and increments the submitted-count
 * analytics snapshot on the dedicated bounded {@code analyticsExecutor}. Because nothing in the 2E
 * build ever submits, this never fires. Async, after-commit, failure-isolated; gated by
 * {@code application.analytics.trigger.enabled}.
 */
@Component
public class AnalyticsWorker {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsWorker.class);

    private final AnalyticsService analytics;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public AnalyticsWorker(AnalyticsService analytics,
                           @Qualifier(ExecutionExecutorsConfig.ANALYTICS_EXECUTOR) ThreadPoolTaskExecutor executor,
                           @Value("${application.analytics.trigger.enabled:false}") boolean triggerEnabled) {
        this.analytics = analytics;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(ExecutionExecutorsConfig.ANALYTICS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        if (!triggerEnabled || !analytics.isEnabled()) return;
        try {
            executor.execute(() -> analytics.record(event.userId(),
                    ExecutionAnalytics.METRIC_APPLICATIONS_SUBMITTED, BigDecimal.ONE));
        } catch (Exception e) {
            log.warn("Analytics worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
