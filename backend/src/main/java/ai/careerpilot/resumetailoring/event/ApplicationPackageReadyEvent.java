package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Phase 2D.6 — published by {@code ApplicationPackageService} once a complete application package
 * version is persisted. Consumer: {@code AutoApplyPreparationWorker} (Phase 2D.7).
 */
public record ApplicationPackageReadyEvent(UUID userId, UUID jobId, UUID applicationPackageId,
                                           int packageVersion) {
}
