package ai.careerpilot.discovery.relevance;

/**
 * Phase 3B.1.1 — soft match-strength bands, replacing the string labels
 * {@link CareerRelevanceScore#matchStrengthFor(int)} used pre-3B.1.1: 90-100 EXCELLENT, 80-89
 * STRONG, 70-79 GOOD, 60-69 WEAK, below 60 HIDDEN.
 */
public enum CareerMatchStrength {
    EXCELLENT, STRONG, GOOD, WEAK, HIDDEN;

    public static CareerMatchStrength fromScore(int score) {
        if (score >= 90) return EXCELLENT;
        if (score >= 80) return STRONG;
        if (score >= 70) return GOOD;
        if (score >= 60) return WEAK;
        return HIDDEN;
    }
}
