package ai.careerpilot.review;

/** Phase 7.12 — the banded overall application-quality category from the Quality Reviewer. */
public enum QualityCategory {
    EXCELLENT, STRONG, GOOD, WEAK, BLOCKED;

    /** Deterministic banding of a 0-100 quality score; BLOCKED is set explicitly by the pipeline, not here. */
    public static QualityCategory fromScore(int score) {
        if (score >= 90) return EXCELLENT;
        if (score >= 75) return STRONG;
        if (score >= 60) return GOOD;
        return WEAK;
    }
}
