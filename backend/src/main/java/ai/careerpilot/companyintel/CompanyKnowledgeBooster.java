package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.13 recommendation integration — a small additive boost/penalty for jobs at companies the
 * knowledge graph already understands, applied by {@code JobMatchingService} AFTER the Phase 6.5
 * learning boost (same seam, same clamping). Derived only from the company's deterministic scores
 * (quality + hiring probability + tech match vs the neutral 50 baseline), capped at ±5 so company
 * knowledge can nudge but never dominate the skill-based match. Gated by BOTH
 * {@code company.knowledge.enabled} and {@code company.recommendation.enabled}: dark = 0 for every
 * job, byte-for-byte identical ranking to today.
 */
@Component
public class CompanyKnowledgeBooster {

    static final int MAX_BOOST = 5;

    private final CompanyKnowledgeRepository companies;
    private final boolean knowledgeEnabled;
    private final boolean recommendationEnabled;

    public CompanyKnowledgeBooster(CompanyKnowledgeRepository companies,
                                   @Value("${company.knowledge.enabled:false}") boolean knowledgeEnabled,
                                   @Value("${company.recommendation.enabled:false}") boolean recommendationEnabled) {
        this.companies = companies;
        this.knowledgeEnabled = knowledgeEnabled;
        this.recommendationEnabled = recommendationEnabled;
    }

    public boolean isActive() {
        return knowledgeEnabled && recommendationEnabled;
    }

    /** Boost for (user, company). 0 when dark, unknown company, or scores are absent. Never throws. */
    public int computeBoost(UUID userId, String companyName) {
        if (!isActive() || companyName == null || companyName.isBlank()) return 0;
        try {
            Optional<CompanyKnowledge> knowledge = companies.findByUserIdAndNormalizedName(
                    userId, CompanyNameNormalizer.normalize(companyName));
            return knowledge.map(CompanyKnowledgeBooster::boostFor).orElse(0);
        } catch (Exception e) {
            return 0; // knowledge must never break matching
        }
    }

    /** Pure: average signed distance of the three signals from the neutral 50, scaled to ±MAX_BOOST. */
    static int boostFor(CompanyKnowledge k) {
        int signals = 0;
        int sum = 0;
        if (k.getQualityScore() != null) { sum += k.getQualityScore() - 50; signals++; }
        if (k.getHiringProbability() != null) { sum += k.getHiringProbability() - 50; signals++; }
        if (k.getTechnologyMatch() != null) { sum += k.getTechnologyMatch() - 50; signals++; }
        if (signals == 0) return 0;
        int scaled = Math.round(sum / (float) signals / 10f);
        return Math.max(-MAX_BOOST, Math.min(MAX_BOOST, scaled));
    }
}
