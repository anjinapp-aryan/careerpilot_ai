package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Published when a user approves a recommended job (Phase 2C {@code RecommendationDecisionService}),
 * consumed by the Resume Tailoring worker to generate a tailored resume. Decoupled via Spring
 * events so a tailoring failure — or the tailoring engine being disabled entirely — can never
 * affect the approval flow that publishes this event (see the module's
 * {@code ResumeTailoringWorker}, which runs {@code @Async} after the publishing transaction
 * commits).
 */
public record RecommendationApprovedEvent(UUID userId, UUID orgId, UUID jobId,
                                          UUID applicationId, UUID recommendationAuditId) {
}
