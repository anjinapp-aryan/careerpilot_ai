package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Phase 2D.3 — published by {@code AtsOptimizationJobService.runJob()} once an ATS analysis
 * reaches {@code SUCCEEDED} (both the manual-API and tailoring-triggered paths go through that one
 * method). First consumer: {@code GapAnalysisWorker}. Same decoupling contract as every pipeline
 * event: async, after-commit, failure-isolated — a downstream failure can never affect the ATS
 * analysis that published it.
 */
public record AtsOptimizedEvent(UUID userId, UUID jobId, UUID resumeTailoringId, UUID atsAnalysisId) {
}
