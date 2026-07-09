package ai.careerpilot.discovery.relevance;

import org.springframework.stereotype.Component;

/**
 * Phase 3B.1 — business-logic layer over {@link RoleFamilyResolver}: decides whether a job's role
 * family is eligible for a given candidate's target role, and produces a 0-100 role-similarity
 * score for {@link CareerRelevanceScore}'s 40% weight.
 */
@Component
public class RoleFamilyService {

    private final RoleFamilyResolver resolver;

    public RoleFamilyService(RoleFamilyResolver resolver) {
        this.resolver = resolver;
    }

    /** Result of comparing a candidate's target role against one job. */
    public record RoleMatchResult(boolean eligible, int similarity, RoleFamily jobFamily, RoleFamily candidateFamily) {}

    /**
     * Evaluate role fit. A job classified {@link RoleFamily#EXCLUDED} is never eligible regardless
     * of the candidate's own family. When the candidate's target role carries no family signal
     * (family {@link RoleFamily#OTHER}), the job is treated as eligible-but-unscored (no false
     * rejection on missing data) with a modest similarity so it doesn't dominate the composite score.
     */
    public RoleMatchResult evaluate(String candidateTargetRole, String jobTitle, String jobDescription) {
        RoleFamily jobFamily = resolver.resolve(jobTitle, jobDescription);
        RoleFamily candidateFamily = resolver.resolve(candidateTargetRole);

        if (jobFamily == RoleFamily.EXCLUDED) {
            return new RoleMatchResult(false, 0, jobFamily, candidateFamily);
        }
        if (candidateFamily == RoleFamily.OTHER) {
            // No signal on the candidate side — don't reject, but don't claim a strong match either.
            return new RoleMatchResult(true, 50, jobFamily, candidateFamily);
        }
        boolean sameFamily = candidateFamily == jobFamily;
        int similarity = sameFamily ? 100 : (jobFamily == RoleFamily.OTHER ? 40 : 0);
        // A job in RoleFamily.OTHER (recognizable as a role, just not one of the four named
        // families) is kept eligible with a low-but-nonzero score rather than hard-rejected —
        // only EXCLUDED jobs are hard-rejected on role.
        boolean eligible = sameFamily || jobFamily == RoleFamily.OTHER;
        return new RoleMatchResult(eligible, similarity, jobFamily, candidateFamily);
    }
}
