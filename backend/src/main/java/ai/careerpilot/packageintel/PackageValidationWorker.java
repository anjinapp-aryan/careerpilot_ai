package ai.careerpilot.packageintel;

import ai.careerpilot.packageintel.config.PackageIntelligenceExecutorsConfig;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
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
 * Phase 7.11 — adds a SECOND listener onto the existing Phase 2D.6 {@link ApplicationPackageReadyEvent}
 * (2D.7's {@code AutoApplyPreparationWorker} is the first) so package validation chains onto assembly
 * without touching any 2D code — the proven "extend an event, don't modify the producer" pattern.
 *
 * <p>Dark by default: gated by BOTH {@code application.package.validation.trigger.enabled} (should this
 * fire off the event) and the engine's own {@code application.package.validation.enabled} flag. Runs on
 * a dedicated bounded executor. Mints/advances a workflow correlation and, on any failure, writes a
 * {@link WorkflowDeadLetterService} row instead of propagating — one failed validation is isolated.
 */
@Component
public class PackageValidationWorker {

    private static final Logger log = LoggerFactory.getLogger(PackageValidationWorker.class);
    private static final String WORKFLOW = "APPLICATION_PACKAGE";
    private static final String STAGE = "PACKAGE_VALIDATION";

    private final ApplicationPackageIntelligenceService intelligence;
    private final ThreadPoolTaskExecutor executor;
    private final WorkflowCorrelationService correlation;
    private final WorkflowDeadLetterService deadLetter;
    private final boolean triggerEnabled;

    public PackageValidationWorker(ApplicationPackageIntelligenceService intelligence,
                                   @Qualifier(PackageIntelligenceExecutorsConfig.PACKAGE_INTELLIGENCE_EXECUTOR)
                                   ThreadPoolTaskExecutor executor,
                                   WorkflowCorrelationService correlation,
                                   WorkflowDeadLetterService deadLetter,
                                   @Value("${application.package.validation.trigger.enabled:false}") boolean triggerEnabled) {
        this.intelligence = intelligence;
        this.executor = executor;
        this.correlation = correlation;
        this.deadLetter = deadLetter;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(PackageIntelligenceExecutorsConfig.PACKAGE_INTELLIGENCE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationPackageReady(ApplicationPackageReadyEvent event) {
        if (!triggerEnabled || !intelligence.isEnabled()) return;
        UUID cid = correlation.start(WORKFLOW, event.userId(), event.jobId(), null);
        try {
            executor.execute(() -> {
                try {
                    intelligence.enrichAndValidate(event.applicationPackageId(), cid);
                } catch (Exception e) {
                    deadLetter.record(cid, WORKFLOW, STAGE, event.toString(), e);
                    log.warn("PACKAGE_VALIDATION worker task failed package={}: {}",
                            event.applicationPackageId(), e.toString());
                }
            });
        } catch (Exception e) {
            deadLetter.record(cid, WORKFLOW, STAGE, event.toString(), e);
            log.warn("PACKAGE_VALIDATION worker dispatch failed package={}: {}",
                    event.applicationPackageId(), e.toString());
        }
    }
}
