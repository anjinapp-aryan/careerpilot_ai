package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Published by {@code ResumeTailoringJobService.runJob()} right after a tailoring job reaches
 * {@code SUCCEEDED} — for both the manual-API path and the approve-triggered path, since both go
 * through that one method. Phase 2D.2's {@code AtsOptimizationWorker} is the first consumer;
 * completes the event chain identified as a gap in the Phase 2D audit
 * (previously {@code RecommendationApprovedEvent -> ResumeTailoringWorker} ended with no
 * downstream event once a tailoring succeeded). Decoupled the same way: async, after-commit,
 * failure-isolated — a downstream consumer failing (or being disabled) can never affect the
 * tailoring that triggered it.
 */
public record ResumeTailoredEvent(UUID userId, UUID jobId, UUID resumeTailoringId, UUID recommendationAuditId) {
}
