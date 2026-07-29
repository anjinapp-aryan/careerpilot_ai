package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.CountryIntelligence;
import ai.careerpilot.domain.SupportedCountry;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.service.profile.JsonLists;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Country Intelligence Engine, Phase 2 — ranks the Phase-1 supported countries against a {@link
 * CareerMission} with a human-readable explanation per country. Deterministic (no LLM), same
 * discipline as {@link InternationalJobScoring}: rule-based now, an explicit future extension
 * point for an LLM-generated explanation layer (Spring AI) once that's warranted — see class-level
 * note in the phase write-up. Input is a single {@code CareerMission}; output is every active
 * supported country, ranked, each with its own fit score and explanation — never a fabricated
 * country outside {@code supported_countries}.
 */
@Component
public class CountryMatchingCapability {

    private final SupportedCountryService countries;
    private final CountryIntelligenceService intelligence;
    private final JobTaxonomy taxonomy;

    public CountryMatchingCapability(SupportedCountryService countries,
                                      CountryIntelligenceService intelligence,
                                      JobTaxonomy taxonomy) {
        this.countries = countries;
        this.intelligence = intelligence;
        this.taxonomy = taxonomy;
    }

    public record CountryFitResult(String countryCode, String displayName, int fitScore, String explanation) {}

    /** Ranked (highest fit first) list of every active supported country for this mission. */
    public List<CountryFitResult> rankForMission(CareerMission mission) {
        List<String> preferredCountries = JsonLists.toList(mission.getTargetCountriesJson());
        Set<String> missionSkills = missionSkillFamilies(mission);
        boolean seniorTarget = isSeniorTarget(mission.getTargetLevel());

        List<CountryFitResult> results = new ArrayList<>();
        for (SupportedCountry country : countries.listActive()) {
            results.add(score(country, preferredCountries, missionSkills, seniorTarget));
        }
        return results.stream()
                .sorted(Comparator.comparingInt(CountryFitResult::fitScore).reversed())
                .toList();
    }

    /** Fit for exactly one country, or empty if it's not (or no longer) an active supported country. */
    public Optional<CountryFitResult> fitForCountry(CareerMission mission, String countryCode) {
        return rankForMission(mission).stream()
                .filter(r -> r.countryCode().equalsIgnoreCase(countryCode))
                .findFirst();
    }

    private CountryFitResult score(SupportedCountry country, List<String> preferredCountries,
                                    Set<String> missionSkills, boolean seniorTarget) {
        Optional<CountryIntelligence> intelOpt = intelligence.forCountry(country.getCountryCode());
        if (intelOpt.isEmpty()) {
            return new CountryFitResult(country.getCountryCode(), country.getDisplayName(), 50,
                    "No curated country intelligence available yet for " + country.getDisplayName() + ".");
        }
        CountryIntelligence intel = intelOpt.get();

        int base = Math.round((intel.getVisaProbabilityScore() + intel.getJobStabilityScore()
                + intel.getTechMarketScore()) / 3f);
        if (seniorTarget) {
            base = Math.round(base * 0.7f + intel.getPrincipalEngineerGrowthScore() * 0.3f);
        }

        List<String> techDemand = JsonLists.toList(intel.getTechnologyDemandJson());
        Set<String> techDemandFamilies = taxonomy.skillFamilies(techDemand);
        long overlap = missionSkills.stream().filter(techDemandFamilies::contains).count();
        int techBonus = (int) Math.min(15, overlap * 5);

        boolean preferred = preferredCountries.stream()
                .anyMatch(p -> p.equalsIgnoreCase(country.getDisplayName()));
        int preferenceBonus = preferred ? 20 : 0;

        int fitScore = Math.min(100, base + techBonus + preferenceBonus);

        String explanation = explain(country, intel, base, techBonus, overlap, preferred, seniorTarget);
        return new CountryFitResult(country.getCountryCode(), country.getDisplayName(), fitScore, explanation);
    }

    private String explain(SupportedCountry country, CountryIntelligence intel, int base,
                            int techBonus, long techOverlap, boolean preferred, boolean seniorTarget) {
        StringBuilder sb = new StringBuilder();
        sb.append(country.getDisplayName()).append(": visa probability ").append(intel.getVisaProbabilityScore())
                .append("/100 (").append(nullToDash(intel.getVisaInformation())).append("), job stability ")
                .append(intel.getJobStabilityScore()).append("/100, tech market ")
                .append(intel.getTechMarketScore()).append("/100");
        if (seniorTarget) {
            sb.append(", principal-engineer growth ").append(intel.getPrincipalEngineerGrowthScore()).append("/100");
        }
        sb.append('.');
        if (techBonus > 0) {
            sb.append(' ').append(techOverlap).append(" of your target/current skills overlap this country's in-demand tech (")
                    .append(String.join(", ", JsonLists.toList(intel.getTechnologyDemandJson()))).append(").");
        }
        if (preferred) {
            sb.append(" Listed among your mission's target countries.");
        }
        return sb.toString();
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private Set<String> missionSkillFamilies(CareerMission mission) {
        List<String> skills = new ArrayList<>(JsonLists.toList(mission.getCurrentSkillsJson()));
        skills.addAll(JsonLists.toList(mission.getSkillsToAcquireJson()));
        return taxonomy.skillFamilies(skills);
    }

    private static boolean isSeniorTarget(String targetLevel) {
        if (targetLevel == null) return false;
        String l = targetLevel.toLowerCase();
        return l.contains("principal") || l.contains("staff") || l.contains("director")
                || l.contains("architect") || l.contains("lead") || l.contains("head");
    }
}
