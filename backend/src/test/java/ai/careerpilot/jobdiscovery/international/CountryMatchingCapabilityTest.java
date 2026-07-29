package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Country Intelligence Engine, Phase 2 — {@link CountryMatchingCapability}. Deterministic (no
 * LLM); pins the preferred-country bonus, the tech-overlap bonus, and the principal-growth blend
 * for senior-level missions, plus graceful degradation when a country has no intelligence row.
 */
class CountryMatchingCapabilityTest {

    private final SupportedCountryService supportedCountries = mock(SupportedCountryService.class);
    private final CountryIntelligenceService intelligenceService = mock(CountryIntelligenceService.class);
    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final CountryMatchingCapability capability =
            new CountryMatchingCapability(supportedCountries, intelligenceService, taxonomy);

    private static SupportedCountry country(String code, String name) {
        return SupportedCountry.builder().countryCode(code).displayName(name).tier(CountryTier.TIER_1).active(true).build();
    }

    private static CountryIntelligence intel(String code, int visa, int jobStability, int techMarket,
                                              int principalGrowth, String techDemandJson) {
        return CountryIntelligence.builder()
                .countryCode(code).visaProbabilityScore(visa).relocationDifficultyScore(50)
                .languageRequirementScore(50).costOfLivingIndex(50).expectedSavingsScore(50)
                .jobStabilityScore(jobStability).techMarketScore(techMarket)
                .principalEngineerGrowthScore(principalGrowth).aiMarketScore(50)
                .technologyDemandJson(techDemandJson)
                .build();
    }

    private static CareerMission mission(String targetLevel, List<String> targetCountries,
                                          List<String> currentSkills, List<String> skillsToAcquire) {
        return CareerMission.builder()
                .missionStatement("stmt").targetRole("role").targetLevel(targetLevel)
                .targetCountriesJson(toJson(targetCountries))
                .currentSkillsJson(toJson(currentSkills))
                .skillsToAcquireJson(toJson(skillsToAcquire))
                .build();
    }

    private static String toJson(List<String> xs) {
        return xs == null || xs.isEmpty() ? null : "[" + String.join(",", xs.stream().map(s -> "\"" + s + "\"").toList()) + "]";
    }

    @Test
    void preferredCountryReceivesABonusOverAnIdenticalNonPreferredOne() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany"), country("nl", "Netherlands")));
        when(intelligenceService.forCountry("de")).thenReturn(Optional.of(intel("de", 70, 70, 70, 70, "[]")));
        when(intelligenceService.forCountry("nl")).thenReturn(Optional.of(intel("nl", 70, 70, 70, 70, "[]")));

        CareerMission mission = mission(null, List.of("Germany"), List.of(), List.of());
        List<CountryFitResult> ranked = capability.rankForMission(mission);

        assertThat(ranked.get(0).countryCode()).isEqualTo("de");
        assertThat(ranked.get(0).fitScore()).isGreaterThan(ranked.get(1).fitScore());
    }

    @Test
    void techOverlapWithCountryDemandIncreasesFitScore() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        when(intelligenceService.forCountry("de"))
                .thenReturn(Optional.of(intel("de", 70, 70, 70, 70, "[\"Java\",\"Spring Boot\"]")));

        CareerMission withOverlap = mission(null, List.of(), List.of("Java", "Spring Boot"), List.of());
        CareerMission withoutOverlap = mission(null, List.of(), List.of("PHP"), List.of());

        int withOverlapScore = capability.rankForMission(withOverlap).get(0).fitScore();
        int withoutOverlapScore = capability.rankForMission(withoutOverlap).get(0).fitScore();

        assertThat(withOverlapScore).isGreaterThan(withoutOverlapScore);
    }

    @Test
    void seniorTargetLevelBlendsInPrincipalEngineerGrowthScore() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        // High principal-growth score, everything else moderate — a senior-targeting mission should score
        // higher than a non-senior one against the identical country.
        when(intelligenceService.forCountry("de")).thenReturn(Optional.of(intel("de", 50, 50, 50, 100, "[]")));

        CareerMission seniorMission = mission("Principal Engineer", List.of(), List.of(), List.of());
        CareerMission midMission = mission("Software Engineer", List.of(), List.of(), List.of());

        int seniorScore = capability.rankForMission(seniorMission).get(0).fitScore();
        int midScore = capability.rankForMission(midMission).get(0).fitScore();

        assertThat(seniorScore).isGreaterThan(midScore);
    }

    @Test
    void missingIntelligenceDegradesGracefullyToNeutralScoreRatherThanThrowing() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("lu", "Luxembourg")));
        when(intelligenceService.forCountry("lu")).thenReturn(Optional.empty());

        List<CountryFitResult> ranked = capability.rankForMission(mission(null, List.of(), List.of(), List.of()));

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).fitScore()).isEqualTo(50);
        assertThat(ranked.get(0).explanation()).contains("No curated country intelligence");
    }

    @Test
    void fitForCountryReturnsOnlyTheRequestedCountry() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany"), country("nl", "Netherlands")));
        when(intelligenceService.forCountry("de")).thenReturn(Optional.of(intel("de", 70, 70, 70, 70, "[]")));
        when(intelligenceService.forCountry("nl")).thenReturn(Optional.of(intel("nl", 60, 60, 60, 60, "[]")));

        Optional<CountryFitResult> result = capability.fitForCountry(mission(null, List.of(), List.of(), List.of()), "nl");

        assertThat(result).isPresent();
        assertThat(result.get().countryCode()).isEqualTo("nl");
    }

    @Test
    void fitForCountryEmptyWhenCountryIsNotActive() {
        when(supportedCountries.listActive()).thenReturn(List.of(country("de", "Germany")));
        when(intelligenceService.forCountry("de")).thenReturn(Optional.of(intel("de", 70, 70, 70, 70, "[]")));

        Optional<CountryFitResult> result = capability.fitForCountry(mission(null, List.of(), List.of(), List.of()), "xx");

        assertThat(result).isEmpty();
    }
}
