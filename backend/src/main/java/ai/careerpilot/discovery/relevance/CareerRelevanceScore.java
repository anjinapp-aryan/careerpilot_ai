package ai.careerpilot.discovery.relevance;

/**
 * Phase 3B.1 — composite career-relevance score: 40% role similarity + 30% skill overlap +
 * 20% experience fit + 10% domain fit. A deliberately different weighting from
 * {@code JobScoring.ScoreBreakdown} (40/25/20/10/3/1/1) — this is a distinct, additive metric for
 * feed eligibility, not a replacement for or modification of the recommendation engine's score.
 */
public record CareerRelevanceScore(int relevanceScore, String matchStrength,
                                   boolean roleMatch, int skillOverlap,
                                   boolean experienceFit, boolean domainFit) {

    private static final double ROLE_WEIGHT = 0.40;
    private static final double SKILL_WEIGHT = 0.30;
    private static final double EXPERIENCE_WEIGHT = 0.20;
    private static final double DOMAIN_WEIGHT = 0.10;

    /** Feed-gating threshold used by Domestic/International (spec: relevance >= 70). */
    public static final int FEED_THRESHOLD = 70;

    public static CareerRelevanceScore from(JobEligibilityEngine.Result eligibility) {
        int experienceComponent = eligibility.experienceMatch() ? 100 : 0;
        int domainComponent = eligibility.domainMatch() ? 100 : 0;

        int total = (int) Math.round(
                eligibility.roleSimilarity() * ROLE_WEIGHT
                        + eligibility.skillOverlap() * SKILL_WEIGHT
                        + experienceComponent * EXPERIENCE_WEIGHT
                        + domainComponent * DOMAIN_WEIGHT);
        total = Math.min(100, Math.max(0, total));

        return new CareerRelevanceScore(total, matchStrengthFor(total),
                eligibility.roleMatch(), eligibility.skillOverlap(),
                eligibility.experienceMatch(), eligibility.domainMatch());
    }

    /** Band label per the Phase 3B.1 spec's confidence-score table. */
    public static String matchStrengthFor(int score) {
        if (score >= 95) return "Strong Match";
        if (score >= 85) return "Excellent Match";
        if (score >= 70) return "Good Match";
        if (score >= 60) return "Weak Match";
        return "Hidden";
    }

    public boolean clearsFeedThreshold() {
        return relevanceScore >= FEED_THRESHOLD;
    }
}
