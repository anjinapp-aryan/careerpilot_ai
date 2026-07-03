package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.service.profile.JsonLists;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Wire shape for {@code /api/candidate-profile}. Structured intelligence only — never resume
 * text. List fields are JSON arrays on the wire (friendly for the React UI) and JSONB in the
 * DB. Controllers return this DTO, never the raw {@link CandidateProfile} entity (project DTO
 * rule — keeps Jackson off the JPA graph).
 */
public record CandidateProfileDto(
        UUID resumeId,
        Integer yearsExperience,
        String currentRole,
        String seniority,
        List<String> skills,
        List<String> targetRoles,
        List<String> domains,
        List<String> languages,
        String homeCountry,
        List<String> preferredCountries,
        List<String> preferredCities,
        List<String> workModes,
        Boolean visaRequired,
        BigDecimal salaryMin,
        BigDecimal salaryTarget,
        String salaryCurrency,
        List<String> excludedRoles,
        String profileSummary,
        BigDecimal confidenceScore,
        List<String> technologies,
        List<String> certifications,
        List<String> industries,
        Boolean leadershipExperience,
        Boolean cloudExpertise,
        List<String> careerGoals,
        Instant updatedAt) {

    public static CandidateProfileDto from(CandidateProfile p) {
        return new CandidateProfileDto(
                p.getResumeId(),
                p.getYearsExperience(),
                p.getCurrentRole(),
                p.getSeniorityLevel(),
                JsonLists.toList(p.getSkillsJson()),
                JsonLists.toList(p.getTargetRolesJson()),
                JsonLists.toList(p.getDomainsJson()),
                JsonLists.toList(p.getLanguagesJson()),
                p.getHomeCountry(),
                JsonLists.toList(p.getPreferredCountriesJson()),
                JsonLists.toList(p.getPreferredCitiesJson()),
                JsonLists.toList(p.getWorkModesJson()),
                p.getVisaRequired(),
                p.getSalaryMin(),
                p.getSalaryTarget(),
                p.getSalaryCurrency(),
                JsonLists.toList(p.getExcludedRolesJson()),
                p.getProfileSummary(),
                p.getConfidenceScore(),
                JsonLists.toList(p.getTechnologiesJson()),
                JsonLists.toList(p.getCertificationsJson()),
                JsonLists.toList(p.getIndustriesJson()),
                p.getLeadershipExperience(),
                p.getCloudExpertise(),
                JsonLists.toList(p.getCareerGoalsJson()),
                p.getUpdatedAt());
    }
}
