package ai.careerpilot.service.profile;

import java.math.BigDecimal;
import java.util.List;

/**
 * Typed result of the AI extraction over a resume — the resume-derived half of a
 * {@link ai.careerpilot.domain.CandidateProfile}. Validated before it ever reaches
 * persistence. Cached against the resume fingerprint so a preferences-only update can
 * re-merge without another LLM call.
 */
public record ResumeIntelligence(
        Integer yearsExperience,
        String currentRole,
        String seniority,
        List<String> skills,
        List<String> targetRoles,
        List<String> domains,
        List<String> languages,
        String profileSummary,
        BigDecimal confidenceScore) {

    /** Empty/unknown intelligence — used as a safe fallback, never persisted as a "success". */
    public static ResumeIntelligence empty() {
        return new ResumeIntelligence(null, null, null, List.of(), List.of(), List.of(), List.of(),
                null, BigDecimal.ZERO);
    }
}
