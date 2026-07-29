package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.service.profile.JsonLists;

import java.util.List;

/**
 * Country Intelligence Engine, Phase 2 — response shapes for {@code CountryController}. Merges
 * {@link SupportedCountry} (identity/tier) and {@link CountryIntelligence} (curated metrics +
 * Phase 2's descriptive fields) into one view — the two entities stay separate for write/seed
 * reasons, but a caller of this API never needs to know that.
 */
public final class CountryDtos {

    private CountryDtos() {}

    public record CountryProfileResponse(
            String countryCode, String countryName, String tier,
            Integer visaProbabilityScore, String visaInformation,
            String salaryInformation,
            List<String> technologyDemand,
            Integer jobStabilityScore, Integer techMarketScore,
            String remotePolicy,
            Integer languageRequirementScore, Integer costOfLivingIndex,
            Integer relocationDifficultyScore, Integer expectedSavingsScore,
            Integer principalEngineerGrowthScore, Integer aiMarketScore,
            Integer priorityScore, String sourceNote
    ) {
        /** {@code intel} is null when a supported country has no curated intelligence row yet. */
        public static CountryProfileResponse from(SupportedCountry country, CountryIntelligence intel) {
            if (intel == null) {
                return new CountryProfileResponse(country.getCountryCode(), country.getDisplayName(),
                        country.getTier().name(), null, null, null, List.of(), null, null, null,
                        null, null, null, null, null, null, null, null);
            }
            return new CountryProfileResponse(
                    country.getCountryCode(), country.getDisplayName(), country.getTier().name(),
                    intel.getVisaProbabilityScore(), intel.getVisaInformation(),
                    intel.getSalaryInformation(), JsonLists.toList(intel.getTechnologyDemandJson()),
                    intel.getJobStabilityScore(), intel.getTechMarketScore(), intel.getRemotePolicy(),
                    intel.getLanguageRequirementScore(), intel.getCostOfLivingIndex(),
                    intel.getRelocationDifficultyScore(), intel.getExpectedSavingsScore(),
                    intel.getPrincipalEngineerGrowthScore(), intel.getAiMarketScore(),
                    intel.getPriorityScore(), intel.getSourceNote());
        }
    }

    public record CareerFitResponse(String countryCode, String displayName, int fitScore, String explanation) {
        public static CareerFitResponse from(CountryFitResult r) {
            return new CareerFitResponse(r.countryCode(), r.displayName(), r.fitScore(), r.explanation());
        }
    }
}
