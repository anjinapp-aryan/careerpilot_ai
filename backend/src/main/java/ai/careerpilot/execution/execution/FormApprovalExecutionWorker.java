package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Gap D — reacts to {@link ApprovalGrantedEvent} exactly like {@code ApplicationExecutionWorker}
 * (Phase 2E.1) and {@code ApplicationSubmissionSessionService#onApprovalGranted} (Phase 7.16), but
 * ONLY for the NEW {@link ApprovalQueueEntry#TYPE_FORM_SCREENSHOT} approval subtype — the "approve
 * this specific filled form" gate — never the original {@link
 * ApprovalQueueEntry#TYPE_APPLICATION_PACKAGE} approval, which {@code ApplicationExecutionWorker}
 * already owns unchanged.
 *
 * <p>{@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} is
 * MANDATORY here, matching this codebase's documented convention: {@link
 * ApprovalService#approve} publishes the event mid-transaction, so a plain {@code @EventListener}
 * would race a dispatch onto another thread against the still-uncommitted approval row — the exact
 * bug already fixed once in {@code ResumeTailoringWorker}/{@code AtsOptimizationWorker} and
 * documented on {@code ApplicationSubmissionSessionService}. Do NOT wrap this listener body in
 * {@code @Transactional(REQUIRES_NEW)} and do NOT dispatch to an executor from inside an open
 * transaction — both would reintroduce that race.
 */
@Component
public class FormApprovalExecutionWorker {

    private static final Logger log = LoggerFactory.getLogger(FormApprovalExecutionWorker.class);

    private final ApprovalService approvalService;
    private final ApplicationExecutionService executionService;
    private final ThreadPoolTaskExecutor executor;

    public FormApprovalExecutionWorker(ApprovalService approvalService,
                                       ApplicationExecutionService executionService,
                                       @Qualifier(ExecutionExecutorsConfig.BROWSER_AUTOMATION_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.approvalService = approvalService;
        this.executionService = executionService;
        this.executor = executor;
    }

    @Async(ExecutionExecutorsConfig.BROWSER_AUTOMATION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalGranted(ApprovalGrantedEvent event) {
        if (!executionService.isEnabled()) return;
        try {
            ApprovalQueueEntry entry = approvalService.findById(event.approvalId()).orElse(null);
            if (entry == null || !ApprovalQueueEntry.TYPE_FORM_SCREENSHOT.equals(entry.getApprovalType())) {
                return; // not our approval subtype — ApplicationExecutionWorker owns package approvals
            }
            if (entry.getExecutionId() == null) {
                log.warn("FORM_APPROVAL_EXECUTION approval {} has no executionId — cannot finalize", entry.getId());
                return;
            }
            executor.execute(() -> executionService.finalizeGuestApplySubmit(entry.getExecutionId()));
        } catch (Exception e) {
            log.warn("Form approval execution worker failed (approval={}): {}", event.approvalId(), e.toString());
        }
    }
}
