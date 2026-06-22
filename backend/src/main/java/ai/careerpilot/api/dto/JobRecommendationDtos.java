package ai.careerpilot.api.dto;

import ai.careerpilot.domain.Job;

import java.util.List;

/** Response shapes for the Stage 1 Recommended Jobs tab. */
public final class JobRecommendationDtos {

    private JobRecommendationDtos() {}

    /** Lightweight candidate snapshot derived from the user's latest workflow run. */
    public record CandidateProfileSummary(
            Integer yearsExperience,
            String currentTitle,
            List<String> topSkills,
            List<String> preferredRoles,
            Integer resumeScore) {}

    public record RecommendedJob(
            Job job,
            int matchScore,
            List<String> matchedSkills,
            List<String> missingSkills) {}

    /** {@code profile} is null when the user has not run the AI workflow yet. */
    public record RecommendedJobsResponse(
            CandidateProfileSummary profile,
            List<RecommendedJob> jobs) {}
}
