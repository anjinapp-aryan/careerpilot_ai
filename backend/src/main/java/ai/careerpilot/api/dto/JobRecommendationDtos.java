package ai.careerpilot.api.dto;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring.ScoreBreakdown;

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

    /**
     * A ranked job. {@code confidenceLevel} and {@code scoreBreakdown} are nullable so the
     * legacy on-the-fly path (no v2 data) serializes the same shape as before — existing
     * clients ignore the new fields.
     */
    public record RecommendedJob(
            Job job,
            int matchScore,
            List<String> matchedSkills,
            List<String> missingSkills,
            String confidenceLevel,
            ScoreBreakdown scoreBreakdown) {

        /** Backward-compatible constructor for the legacy (no breakdown/confidence) path. */
        public RecommendedJob(Job job, int matchScore, List<String> matchedSkills, List<String> missingSkills) {
            this(job, matchScore, matchedSkills, missingSkills, null, null);
        }
    }

    /**
     * {@code profile} is null when the user has not run the AI workflow yet. {@code page}/{@code size}/
     * {@code total}/{@code hasMore} are additive pagination metadata — existing clients that only read
     * {@code profile} + {@code jobs} are unaffected.
     */
    public record RecommendedJobsResponse(
            CandidateProfileSummary profile,
            List<RecommendedJob> jobs,
            int page,
            int size,
            int total,
            boolean hasMore) {

        /** Backward-compatible constructor (no pagination metadata). */
        public RecommendedJobsResponse(CandidateProfileSummary profile, List<RecommendedJob> jobs) {
            this(profile, jobs, 0, jobs == null ? 0 : jobs.size(), jobs == null ? 0 : jobs.size(), false);
        }
    }
}
