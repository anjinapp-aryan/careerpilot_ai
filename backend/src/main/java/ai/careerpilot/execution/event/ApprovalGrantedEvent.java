package ai.careerpilot.execution.event;

import java.util.UUID;

/**
 * Phase 2E.4 — published ONLY by the human approve endpoint ({@code POST /api/execution/approve})
 * after an {@code approval_queue} row transitions PENDING -> APPROVED. There is deliberately no
 * automatic publisher: nothing downstream (the Application Execution Engine) runs without a human
 * decision. Consumer: {@code ApplicationExecutionWorker} (Phase 2E.1).
 */
public record ApprovalGrantedEvent(UUID userId, UUID jobId, UUID applicationPackageId,
                                   UUID approvalId, String decidedBy) {
}
