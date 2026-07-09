package ai.careerpilot.autopilot.resume;

/**
 * Phase 7.2 — outcome of {@link ResumeSelectionEngine}.
 *
 * <ul>
 *   <li>{@code SELECTED} — a suitable tailored resume version already exists for this job.</li>
 *   <li>{@code NEEDS_TAILORING} — a base resume exists but no suitable tailored version yet; the
 *       7.3 pipeline should generate one before applying.</li>
 *   <li>{@code NO_BASE_RESUME} — the user has no base resume at all; nothing to tailor from.</li>
 * </ul>
 */
public enum SelectionOutcome {
    SELECTED,
    NEEDS_TAILORING,
    NO_BASE_RESUME
}
