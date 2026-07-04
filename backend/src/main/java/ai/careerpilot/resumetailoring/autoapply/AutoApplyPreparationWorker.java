package ai.careerpilot.resumetailoring.autoapply;

import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
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
 * Phase 2D.7 — reacts to {@link ApplicationPackageReadyEvent} and runs the deterministic
 * auto-apply readiness assessment on the dedicated bounded {@code autoApplyPreparationExecutor}.
 * The final stage of the pipeline — publishes no further event. Async, after-commit,
 * failure-isolated; gated by {@code auto.apply.trigger.enabled}, independent of
 * {@code auto.apply.package.enabled}.
 */
@Component
public class AutoApplyPreparationWorker {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyPreparationWorker.class);

    private final AutoApplyPreparationService preparation;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public AutoApplyPreparationWorker(AutoApplyPreparationService preparation,
                                      @Qualifier(PipelineExecutorsConfig.AUTO_APPLY_PREPARATION_EXECUTOR) ThreadPoolTaskExecutor executor,
                                      @Value("${auto.apply.trigger.enabled:false}") boolean triggerEnabled) {
        this.preparation = preparation;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(PipelineExecutorsConfig.AUTO_APPLY_PREPARATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationPackageReady(ApplicationPackageReadyEvent event) {
        if (!triggerEnabled || !preparation.isEnabled()) return;
        try {
            executor.execute(() -> preparation.prepare(event.userId(), event.jobId(), event.applicationPackageId()));
        } catch (Exception e) {
            log.warn("Auto apply preparation worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
