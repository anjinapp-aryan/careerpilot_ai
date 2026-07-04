package ai.careerpilot.resumetailoring.apppackage;

import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.event.CoverLetterCompletedEvent;
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
 * Phase 2D.6 — reacts to {@link CoverLetterCompletedEvent} and assembles the application package
 * on the dedicated bounded {@code applicationPackageExecutor}. Async, after-commit,
 * failure-isolated; gated by {@code application.package.trigger.enabled}, independent of
 * {@code application.package.enabled}.
 */
@Component
public class ApplicationPackageWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPackageWorker.class);

    private final ApplicationPackageService packages;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public ApplicationPackageWorker(ApplicationPackageService packages,
                                    @Qualifier(PipelineExecutorsConfig.APPLICATION_PACKAGE_EXECUTOR) ThreadPoolTaskExecutor executor,
                                    @Value("${application.package.trigger.enabled:false}") boolean triggerEnabled) {
        this.packages = packages;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCoverLetterCompleted(CoverLetterCompletedEvent event) {
        if (!triggerEnabled || !packages.isEnabled()) return;
        try {
            executor.execute(() -> packages.assemble(event.userId(), event.jobId()));
        } catch (Exception e) {
            log.warn("Application package worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
