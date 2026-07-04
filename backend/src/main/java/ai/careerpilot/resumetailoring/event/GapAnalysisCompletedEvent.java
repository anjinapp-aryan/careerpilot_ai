package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Phase 2D.3 — published by {@code GapAnalysisService} once a {@code resume_gap_analysis} row is
 * persisted. Consumer: {@code AtsExplainabilityWorker} (Phase 2D.4).
 */
public record GapAnalysisCompletedEvent(UUID userId, UUID jobId, UUID resumeTailoringId,
                                        UUID atsAnalysisId, UUID gapAnalysisId) {
}
