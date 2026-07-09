package ai.careerpilot.discovery.relevance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 3B.1 — filters out roles whose stated required experience is far below a senior
 * candidate's own years, so a 12-yr Java Architect doesn't see 0-2yr/junior postings in a
 * career-aware feed. One-directional by design (never rejects a job for requiring <i>more</i>
 * experience than the candidate has) — that direction isn't part of the "junior-noise" problem
 * this phase targets, and 2C/2B already softly weight seniority fit elsewhere.
 *
 * <p>Rule: eligible when {@code job.requiredExperience >= candidateYears - tolerance} (default
 * tolerance 3 years — reproduces every bucket example in the Phase 3B.1 spec exactly: a 12-yr
 * candidate against jobs requiring 2/5/8 years reject, and 15/20 years allow, using
 * {@code threshold = 12 - 3 = 9}). Either side missing is treated as "no signal" and never rejects.
 */
@Component
public class ExperienceEligibilityService {

    private final int toleranceYears;

    public ExperienceEligibilityService(
            @Value("${career.relevance.experience-tolerance-years:3}") int toleranceYears) {
        this.toleranceYears = toleranceYears;
    }

    /** True when the job is not too junior for this candidate (or there's no signal either way). */
    public boolean isEligible(Integer candidateYears, Integer jobRequiredExperience) {
        if (candidateYears == null || jobRequiredExperience == null) return true;
        return jobRequiredExperience >= (candidateYears - toleranceYears);
    }
}
