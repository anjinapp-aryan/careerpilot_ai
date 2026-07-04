package ai.careerpilot.execution.event;

import java.util.UUID;

/**
 * Phase 2E.5 — published by {@code SafetyValidationWorker} when an application package passes the
 * deterministic safety gate with verdict SAFE or REVIEW (BLOCK ends the chain silently, audited).
 * Consumer: {@code ApprovalWorker} (Phase 2E.4), which parks a PENDING approval-queue row.
 *
 * @param verdict SAFE or REVIEW (BLOCK never produces this event)
 */
public record SafetyValidatedEvent(UUID userId, UUID jobId, UUID applicationPackageId,
                                   String verdict) {
}
