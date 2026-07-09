package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.CompanyRelationship;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.CompanyRelationshipRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7.13 resume-tailoring integration — surfaces the target company's OBSERVED keyword demand
 * (technology/skill graph edges, ranked by observation weight) so the tailoring prompt can
 * emphasize matching real experience. Gated by BOTH {@code company.knowledge.enabled} and
 * {@code company.resume.enabled}; dark or unknown company returns an empty list, which leaves the
 * tailoring prompt byte-for-byte unchanged.
 */
@Component
public class CompanyResumeHints {

    private static final int MAX_HINTS = 12;

    private final CompanyKnowledgeRepository companies;
    private final CompanyRelationshipRepository relationships;
    private final boolean knowledgeEnabled;
    private final boolean resumeEnabled;

    public CompanyResumeHints(CompanyKnowledgeRepository companies,
                              CompanyRelationshipRepository relationships,
                              @Value("${company.knowledge.enabled:false}") boolean knowledgeEnabled,
                              @Value("${company.resume.enabled:false}") boolean resumeEnabled) {
        this.companies = companies;
        this.relationships = relationships;
        this.knowledgeEnabled = knowledgeEnabled;
        this.resumeEnabled = resumeEnabled;
    }

    public boolean isActive() {
        return knowledgeEnabled && resumeEnabled;
    }

    /** Observed keyword demand for (user, company), strongest signal first. Never throws. */
    public List<String> keywordHints(UUID userId, String companyName) {
        if (!isActive() || companyName == null || companyName.isBlank()) return List.of();
        try {
            CompanyKnowledge company = companies.findByUserIdAndNormalizedName(
                    userId, CompanyNameNormalizer.normalize(companyName)).orElse(null);
            if (company == null) return List.of();
            return relationships.findByCompanyKnowledgeId(company.getId()).stream()
                    .filter(e -> CompanyRelationship.TYPE_TECHNOLOGY.equals(e.getRelationType())
                            || CompanyRelationship.TYPE_SKILL.equals(e.getRelationType()))
                    .sorted(Comparator.comparingInt(CompanyRelationship::getWeight).reversed())
                    .map(CompanyRelationship::getTarget)
                    .distinct()
                    .limit(MAX_HINTS)
                    .toList();
        } catch (Exception e) {
            return List.of(); // hints must never break tailoring
        }
    }
}
