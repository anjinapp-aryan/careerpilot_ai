package ai.careerpilot.execution.approval;

import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.event.SafetyValidatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2E.4 — reacts to a {@link SafetyValidatedEvent} (verdict SAFE/REVIEW) and parks a PENDING
 * approval-queue row, then STOPS. This worker deliberately does NOT advance the pipeline: the only
 * way past it is a human calling {@code POST /api/execution/approve}. Async, after-commit,
 * failure-isolated; runs on the front-of-pipeline {@code applicationExecutionExecutor} (the spec
 * defines no separate approval executor). Gated by {@code application.approval.enabled}.
 */
@Component
public class ApprovalWorker {

    private static final Logger log = LoggerFactory.getLogger(ApprovalWorker.class);

    private final ApprovalService approval;
    private final ThreadPoolTaskExecutor executor;

    public ApprovalWorker(ApprovalService approval,
                          @Qualifier(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.approval = approval;
        this.executor = executor;
    }

    @Async(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSafetyValidated(SafetyValidatedEvent event) {
        if (!approval.isEnabled()) return;
        try {
            executor.execute(() -> approval.enqueue(
                    event.userId(), event.jobId(), event.applicationPackageId(), event.verdict()));
        } catch (Exception e) {
            log.warn("Approval worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
