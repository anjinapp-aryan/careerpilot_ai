package ai.careerpilot.execution.safety;

/**
 * Phase 2E.5 — the deterministic safety gate's three-valued output.
 * <ul>
 *   <li>{@code SAFE} — all checks pass; the package may proceed to human approval.</li>
 *   <li>{@code REVIEW} — soft concerns (low ATS score, large gap, off-preference); proceeds to
 *       approval but flagged for the human's attention.</li>
 *   <li>{@code BLOCK} — a hard failure (missing artifact, duplicate application, blacklist/excluded);
 *       the pipeline ends here, audited, and never reaches approval or execution.</li>
 * </ul>
 */
public enum SafetyVerdict {
    SAFE,
    REVIEW,
    BLOCK
}
