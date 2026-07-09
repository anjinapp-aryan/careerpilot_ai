package ai.careerpilot.discovery.relevance;

import java.util.List;

/**
 * Phase 3B.1 — Step 10 explainability payload. Wire shape matches the spec exactly:
 * {@code relevanceScore, roleMatch, skillOverlap, experienceFit, domainFit, reasons}.
 */
public record CareerRelevanceExplanation(
        int relevanceScore,
        String matchStrength,
        boolean roleMatch,
        int skillOverlap,
        boolean experienceFit,
        boolean domainFit,
        List<String> reasons) {
}
