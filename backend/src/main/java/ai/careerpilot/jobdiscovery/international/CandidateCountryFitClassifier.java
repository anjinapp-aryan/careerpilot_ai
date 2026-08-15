package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.jobdiscovery.IndustryFit;
import ai.careerpilot.jobdiscovery.IndustryFitClassifier;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * International Job Discovery Phase 2 — deterministic candidate-to-country fit. Pure: no
 * database access, no network, no LLM, same discipline as {@code RetryPolicyService}/{@code
 * VerificationAdjudicator} elsewhere in this codebase. Reusable for any candidate — nothing here
 * names a specific country as a hardcoded winner; the result falls out of the inputs.
 *
 * <p>Deliberately reuses {@link IndustryFitClassifier} rather than inventing a second industry
 * detector: the candidate's own inferred industry affinity is computed by running their target
 * role + skills text through the exact same classifier a job posting's text goes through, then
 * checking whether the country's curated {@code industryFit} list contains it.
 *
 * <p>Returns {@code null} — never a guessed default — when {@code intel} is {@code null}: a
 * country with no curated intelligence has nothing this classifier can honestly evaluate.
 */
@Component
public class CandidateCountryFitClassifier {

    private final IndustryFitClassifier industryFitClassifier;

    public CandidateCountryFitClassifier(IndustryFitClassifier industryFitClassifier) {
        this.industryFitClassifier = industryFitClassifier;
    }

    public CandidateCountryFit classify(List<String> candidateSkills, String targetRole, CountryIntelligence intel) {
        if (intel == null) return null;

        int techMarket = intel.getTechMarketScore();
        int language = intel.getLanguageFriendlyScore() != null ? intel.getLanguageFriendlyScore() : 50;
        int visa = intel.getVisaProbabilityScore();
        int industryOverlap = industryOverlapScore(candidateSkills, targetRole, intel);

        int score = Math.round(techMarket * 0.35f + language * 0.20f + visa * 0.20f + industryOverlap * 0.25f);
        score = Math.min(100, Math.max(0, score));

        if (score >= 85) return CandidateCountryFit.VERY_HIGH;
        if (score >= 70) return CandidateCountryFit.HIGH;
        if (score >= 50) return CandidateCountryFit.MEDIUM;
        return CandidateCountryFit.LOW;
    }

    /**
     * 100 when the candidate's own inferred industry (from their target role + skills, via the
     * shared {@link IndustryFitClassifier}) appears in the country's curated {@code industryFit}
     * list; 50 (neutral) when either side has no signal; 30 when the candidate has a clear
     * industry affinity that this country's curated list does NOT claim to serve.
     */
    private int industryOverlapScore(List<String> candidateSkills, String targetRole, CountryIntelligence intel) {
        String skillsText = candidateSkills == null ? "" : String.join(" ", candidateSkills);
        IndustryFit candidateIndustry = industryFitClassifier.classify(targetRole, skillsText);
        if (candidateIndustry == IndustryFit.UNKNOWN) return 50;

        List<String> countryIndustries = JsonLists.toList(intel.getIndustryFitJson());
        if (countryIndustries.isEmpty()) return 50;
        boolean overlap = countryIndustries.stream().anyMatch(i -> i.equalsIgnoreCase(candidateIndustry.name()));
        return overlap ? 100 : 30;
    }
}
