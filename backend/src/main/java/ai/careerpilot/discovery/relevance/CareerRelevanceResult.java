package ai.careerpilot.discovery.relevance;

import java.util.List;

/**
 * Phase 3B.1.1 — scope-aware evaluation result: the raw score, its {@link CareerMatchStrength}
 * band, whether it clears the threshold configured for the requesting feed (Recommended/Domestic/
 * International/Browse), and the same deterministic reason strings as
 * {@link CareerRelevanceExplanation}. Additive alongside {@link CareerRelevanceExplanation} rather
 * than replacing it, so the existing {@code GET /api/jobs/{id}/relevance} contract is untouched.
 */
public record CareerRelevanceResult(
        int score,
        CareerMatchStrength strength,
        boolean visible,
        List<String> reasons) {
}
