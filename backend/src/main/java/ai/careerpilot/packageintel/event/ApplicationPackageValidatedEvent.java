package ai.careerpilot.packageintel.event;

import java.util.UUID;

/**
 * Phase 7.11 → 7.12 seam — published by {@code ApplicationPackageIntelligenceService} after a package
 * has been enriched and validated. It carries the validation verdict and workflow correlation forward
 * so the Phase 7.12 AI Review Pipeline can chain <em>after</em> validation and <em>before</em> the
 * Application Decision, without any consumer having to re-run validation. Every consumer is dark by
 * default, so with stock flags this event goes nowhere.
 */
public record ApplicationPackageValidatedEvent(UUID userId, UUID jobId, UUID applicationPackageId,
                                               int packageVersion, String validationStatus,
                                               UUID correlationId) {
}
