package ai.careerpilot.learning;

/**
 * Phase 6.1 — the 14 outcome-bearing signal types the learning engine observes. Not every value is
 * currently emitted by {@code LearningEventBridge}: RESUME_REJECTED, RECOMMENDATION_REJECTED, and
 * WORKFLOW_FAILED have no corresponding existing domain event to attach to (no reject event exists
 * for recommendations/resumes, and workers only log+dead-letter on failure, they don't publish a
 * "failed" event) — the enum values exist per spec but stay unused until such an event exists.
 */
public enum LearningEventType {
    APPLICATION_SUBMITTED,
    APPLICATION_REJECTED,
    APPLICATION_ACCEPTED,
    INTERVIEW_SCHEDULED,
    INTERVIEW_COMPLETED,
    OFFER_RECEIVED,
    OFFER_ACCEPTED,
    OFFER_REJECTED,
    RESUME_SELECTED,
    RESUME_REJECTED,
    RECOMMENDATION_APPROVED,
    RECOMMENDATION_REJECTED,
    WORKFLOW_COMPLETED,
    WORKFLOW_FAILED
}
