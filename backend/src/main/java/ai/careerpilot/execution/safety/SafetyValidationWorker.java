package ai.careerpilot.execution.safety;

import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.event.SafetyValidatedEvent;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2E.5 — the ENTRY point of the execution pipeline. Reacts to the existing 2D.6
 * {@link ApplicationPackageReadyEvent} (a second listener alongside 2D.7's AutoApplyPreparationWorker),
 * runs the {@link SafetyEngine}, and on a non-BLOCK verdict publishes a {@link SafetyValidatedEvent}
 * to hand off to the approval stage. A BLOCK verdict ends the chain here (audited via SafetyMetrics).
 *
 * <p>Gated by a dedicated {@code application.safety.trigger.enabled} flag that defaults <b>false</b>:
 * although the safety gate itself is enabled by default (it must never be skippable), the pipeline
 * does NOT auto-start off 2D output unless this trigger is explicitly flipped — so with stock
 * defaults, completing a 2D pipeline creates ZERO execution/approval rows. Async, after-commit,
 * failure-isolated.
 */
@Component
public class SafetyValidationWorker {

    private static final Logger log = LoggerFactory.getLogger(SafetyValidationWorker.class);

    private final SafetyEngine safety;
    private final ApplicationEventPublisher events;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public SafetyValidationWorker(SafetyEngine safety, ApplicationEventPublisher events,
                                  @Qualifier(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR) ThreadPoolTaskExecutor executor,
                                  @Value("${application.safety.trigger.enabled:false}") boolean triggerEnabled) {
        this.safety = safety;
        this.events = events;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationPackageReady(ApplicationPackageReadyEvent event) {
        if (!triggerEnabled || !safety.isEnabled()) return;
        try {
            executor.execute(() -> validate(event));
        } catch (Exception e) {
            log.warn("Safety validation worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }

    private void validate(ApplicationPackageReadyEvent event) {
        SafetyResult result = safety.evaluate(event.userId(), event.jobId(), event.applicationPackageId());
        if (result.verdict() == SafetyVerdict.BLOCK) {
            log.info("SAFETY_BLOCK user={} job={} — pipeline ends: {}",
                    event.userId(), event.jobId(), result.reasonSummary());
            return;
        }
        events.publishEvent(new SafetyValidatedEvent(
                event.userId(), event.jobId(), event.applicationPackageId(), result.verdict().name()));
    }
}
