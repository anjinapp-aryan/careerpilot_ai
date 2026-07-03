package ai.careerpilot.resumetailoring.llm;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * All read-only inputs assembled for one tailoring generation: original resume text, candidate
 * profile signals, behavior-profile preferences, job intelligence, and (if cached) the
 * recommendation explanation. Built once per generation by {@code ResumeTailoringService} from
 * existing Phase 1 / 2B / 2C data — this record never persists anything itself.
 */
public record TailoringContext(
        UUID userId,
        UUID jobId,
        UUID originalResumeId,
        String originalResumeText,
        UUID recommendationAuditId,
        UUID candidateProfileVersion,
        Instant behaviorProfileVersion,

        // Candidate Profile (Phase 1)
        List<String> profileSkills,
        List<String> targetRoles,
        Integer yearsExperience,
        List<String> technologies,
        List<String> certifications,

        // Behavior Profile (Phase 2C-5)
        List<String> preferredRoles,
        List<String> preferredWorkModes,

        // Job Intelligence (Phase 2B) — job title/company always present; enrichment fields optional
        String jobTitle,
        String jobCompany,
        String jobDescription,
        List<String> jobSkills,
        String roleFamily,
        String jobDomain,
        String jobCountry,

        // Recommendation Explanation (Phase 2C explainability), may be entirely absent
        String matchingSkills,
        String missingSkills,
        String resumeImprovements
) {
}
