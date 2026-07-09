package ai.careerpilot.execution.execution;

import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
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
 * Phase 2E.1 — reacts to {@link ApprovalGrantedEvent} (published ONLY by the human approve endpoint)
 * and runs one execution attempt on the dedicated bounded {@code applicationExecutionExecutor}.
 * Async, after-commit, failure-isolated — any exception (including a saturated queue's
 * {@code TaskRejectedException}) is caught and logged, never propagated. Gated by its own
 * {@code application.execution.trigger.enabled} flag, independent of {@code application.execution.enabled}.
 *
 * <p>This is the sole consumer of {@code ApprovalGrantedEvent}, which no automatic path publishes —
 * so this worker never fires without a human decision, and even then {@link ApplicationExecutionService}
 * can only reach a terminal {@code ABORTED} in the 2E build (no execution backend).
 */
@Component
public class ApplicationExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(ApplicationExecutionWorker.class);

    private final ApplicationExecutionService execution;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public ApplicationExecutionWorker(ApplicationExecutionService execution,
                                      @Qualifier(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR) ThreadPoolTaskExecutor executor,
                                      @Value("${application.execution.trigger.enabled:false}") boolean triggerEnabled) {
        this.execution = execution;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalGranted(ApprovalGrantedEvent event) {
        if (!triggerEnabled || !execution.isEnabled()) return;
        try {
            executor.execute(() -> execution.execute(event.userId(), event.jobId(), event.applicationPackageId()));
        } catch (Exception e) {
            log.warn("Application execution worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
