package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * International Job Discovery Engine, Phase 1 — the fixed 27-title allow-list from the program
 * spec (Technology roles only). Matching is whole-word-OR-role-family (same style as
 * {@link ai.careerpilot.jobdiscovery.RoleExclusionFilter}), not a brittle exact-string match, so
 * a real-world listing like "Sr. Backend Engineer II" still matches "Backend Engineer" via role
 * family overlap even though the literal strings differ.
 */
@Component
public class InternationalRoleTaxonomy {

    /** The exact 27 titles from the program spec, normalized lowercase. */
    public static final List<String> ALLOWED_TITLES = List.of(
            "backend engineer", "senior java developer", "senior software engineer",
            "staff software engineer", "principal software engineer", "software architect",
            "technical architect", "platform engineer", "cloud engineer", "devops engineer",
            "site reliability engineer", "ai engineer", "machine learning engineer",
            "data engineer", "engineering manager", "technical lead", "full stack engineer",
            "frontend engineer", "react developer", "spring boot developer",
            "microservices engineer", "aws engineer", "azure engineer", "gcp engineer",
            "kubernetes engineer", "security engineer", "database engineer");

    private final JobTaxonomy taxonomy;

    public InternationalRoleTaxonomy(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * True when the job title directly contains one of the 27 allow-list phrases, OR its coarse
     * role family (via the shared {@link JobTaxonomy}) overlaps a role family implied by the
     * allow-list — keeps recall reasonable for real-world title variants.
     */
    public boolean matchesAllowedTitle(Job job) {
        String title = job.getTitle();
        if (title == null || title.isBlank()) return false;
        String lower = title.toLowerCase();
        for (String allowed : ALLOWED_TITLES) {
            if (containsToken(lower, allowed)) return true;
        }
        Set<String> jobFamilies = taxonomy.roleFamilies(title);
        if (jobFamilies.isEmpty()) return false;
        for (String allowed : ALLOWED_TITLES) {
            Set<String> allowedFamilies = taxonomy.roleFamilies(allowed);
            if (!java.util.Collections.disjoint(jobFamilies, allowedFamilies)) return true;
        }
        return false;
    }

    /** Word-boundary substring match, mirroring the whole-word convention used across this package. */
    private static boolean containsToken(String haystack, String phrase) {
        return Pattern.compile("\\b" + Pattern.quote(phrase) + "\\b").matcher(haystack).find();
    }
}
