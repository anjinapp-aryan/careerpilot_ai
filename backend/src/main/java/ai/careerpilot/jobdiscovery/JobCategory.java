package ai.careerpilot.jobdiscovery;

/**
 * Phase 2B-1 (Step 7) → refined in Phase 2C-2 (Step 2) — the action bucket a recommendation falls
 * into, derived purely from its match score by {@link JobCategorizer}. Persisted as a string on
 * {@code job_recommendations.category}.
 *
 * <p>The 2C-2 taxonomy is a 5-tier refinement of the original 4-tier one (AUTO_APPLY→AUTO_APPLY_READY,
 * a new HIGH_PRIORITY band split off the top of HUMAN_REVIEW, GOOD_MATCH→RECOMMENDED, IGNORE→ARCHIVED).
 * Nothing parses this back into the enum, so the rename is safe on already-stored rows (V20 remaps the
 * two renamed-only values; threshold-shifted rows self-recompute on their next refresh).
 */
public enum JobCategory {
    /** 95+ — eligible for future auto-apply automation (does NOT apply automatically in Phase 2C). */
    AUTO_APPLY_READY,
    /** 90–94 — a top match worth prioritising for human review. */
    HIGH_PRIORITY,
    /** 80–89 — surface for a human to review before applying. */
    HUMAN_REVIEW,
    /** 70–79 — a solid match worth showing. */
    RECOMMENDED,
    /** below 70 — not worth surfacing as a recommendation. */
    ARCHIVED
}
