package ai.careerpilot.service.profile.event;

import java.util.UUID;

/**
 * Published when a user's resume materially changes (uploaded, replaced, or optimized).
 * Consumed by the Candidate Profile module to trigger a fresh AI extraction. Source services
 * publish this and do not depend on the profile module (decoupled via Spring events) — a
 * downstream failure can never affect resume upload/optimization.
 *
 * @param reason {@link #REASON_UPLOADED} or {@link #REASON_OPTIMIZED}
 */
public record ResumeChangedEvent(UUID userId, UUID resumeId, String reason) {

    public static final String REASON_UPLOADED = "RESUME_UPLOADED";
    public static final String REASON_OPTIMIZED = "RESUME_OPTIMIZED";

    public static ResumeChangedEvent uploaded(UUID userId, UUID resumeId) {
        return new ResumeChangedEvent(userId, resumeId, REASON_UPLOADED);
    }

    public static ResumeChangedEvent optimized(UUID userId, UUID resumeId) {
        return new ResumeChangedEvent(userId, resumeId, REASON_OPTIMIZED);
    }
}
