package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CandidatePreferences;
import ai.careerpilot.jobdiscovery.JobScoring;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for {@code /api/candidate/preferences}. Location lists are JSON arrays on the
 * wire (friendlier for the React form) but comma-joined in the DB column.
 */
public record CandidatePreferencesDto(
        List<String> preferredCountries,
        List<String> preferredCities,
        List<String> preferredRoles,
        boolean remotePreference,
        boolean hybridPreference,
        boolean onsitePreference,
        boolean visaSponsorshipRequired,
        boolean relocationRequired,
        BigDecimal salaryExpectationMin,
        BigDecimal salaryExpectationMax,
        String salaryCurrency) {

    /** Defaults used when a user has never saved preferences. */
    public static CandidatePreferencesDto defaults() {
        return new CandidatePreferencesDto(List.of(), List.of(), List.of(), false, false, false, false, false,
                null, null, null);
    }

    public static CandidatePreferencesDto from(CandidatePreferences e) {
        return new CandidatePreferencesDto(
                csv(e.getPreferredCountries()), csv(e.getPreferredCities()), csv(e.getPreferredRoles()),
                e.isRemotePreference(), e.isHybridPreference(), e.isOnsitePreference(),
                e.isVisaSponsorshipRequired(), e.isRelocationRequired(),
                e.getSalaryExpectationMin(), e.getSalaryExpectationMax(), e.getSalaryCurrency());
    }

    public CandidatePreferences toEntity(UUID userId) {
        return CandidatePreferences.builder()
                .userId(userId)
                .preferredCountries(join(preferredCountries))
                .preferredCities(join(preferredCities))
                .preferredRoles(join(preferredRoles))
                .remotePreference(remotePreference)
                .hybridPreference(hybridPreference)
                .onsitePreference(onsitePreference)
                .visaSponsorshipRequired(visaSponsorshipRequired)
                .relocationRequired(relocationRequired)
                .salaryExpectationMin(salaryExpectationMin)
                .salaryExpectationMax(salaryExpectationMax)
                .salaryCurrency(salaryCurrency)
                .build();
    }

    public List<String> preferredRolesOrEmpty() {
        return preferredRoles == null ? List.of() : preferredRoles;
    }

    /** Map to the scorer's preference view. */
    public JobScoring.PreferenceContext toScoringContext() {
        return new JobScoring.PreferenceContext(
                preferredCountries == null ? List.of() : preferredCountries,
                preferredCities == null ? List.of() : preferredCities,
                remotePreference, hybridPreference, onsitePreference, visaSponsorshipRequired,
                salaryExpectationMin, salaryExpectationMax);
    }

    private static List<String> csv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static String join(List<String> xs) {
        if (xs == null || xs.isEmpty()) return null;
        return String.join(",", xs.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }
}
