package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Phase 2D.4 — published by {@code AtsExplainabilityService} once a {@code resume_ats_explanation}
 * row is persisted. Consumer: {@code CoverLetterWorker} (Phase 2D.5).
 */
public record AtsExplainabilityCompletedEvent(UUID userId, UUID jobId, UUID resumeTailoringId,
                                              UUID atsAnalysisId, UUID gapAnalysisId, UUID explanationId) {
}
