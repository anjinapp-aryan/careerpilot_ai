package ai.careerpilot.autopilot.resume;

/**
 * Phase 7.3 — outcome of {@link AutopilotTailoringTrigger}.
 *
 * <ul>
 *   <li>{@code ALREADY_READY} — a suitable tailored resume already exists; nothing triggered.</li>
 *   <li>{@code TAILORING_TRIGGERED} — the existing Phase 2D tailoring→ATS pipeline was kicked off
 *       (via {@code RecommendationApprovedEvent}); a new version will be generated asynchronously.</li>
 *   <li>{@code NO_BASE_RESUME} — the user has no base resume to tailor from.</li>
 *   <li>{@code NOT_TRIGGERED} — the trigger or resume-selection engine is disabled.</li>
 * </ul>
 */
public enum TailoringTriggerOutcome {
    ALREADY_READY,
    TAILORING_TRIGGERED,
    NO_BASE_RESUME,
    NOT_TRIGGERED
}
