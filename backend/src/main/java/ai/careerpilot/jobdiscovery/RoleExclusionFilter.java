package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * User-defined role exclusion, shared by the rule-based matcher ({@link JobMatchingService}) and the
 * Domestic/International discovery tabs ({@link ai.careerpilot.service.JobService}) so a role the user
 * excluded never surfaces in either surface. Two conservative tests keep it from over-filtering:
 * <ol>
 *   <li><b>Whole-word title match</b> — "Sales" excludes "Sales Executive" but NOT "Salesforce
 *       Engineer" (word boundary), and never fires on a blank/partial token.</li>
 *   <li><b>Non-tech family match</b> — the excluded term resolves (via the taxonomy) to a
 *       non-technical family equal to the job's family (e.g. excluding "Marketing" drops the
 *       MARKETING family). Excluded terms that are themselves technical never family-match, so
 *       excluding "Backend" won't nuke every engineering role by family.</li>
 * </ol>
 * Empty/absent exclusions are a no-op — this is why the filter is safe to apply always-on.
 */
@Component
public class RoleExclusionFilter {

    private final JobTaxonomy taxonomy;

    public RoleExclusionFilter(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /** True when this job should be hidden because its title/family matches a user-excluded role. */
    public boolean isExcluded(Job job, List<String> excludedRoles) {
        if (excludedRoles == null || excludedRoles.isEmpty() || job.getTitle() == null) return false;
        String title = job.getTitle();
        String jobFamily = taxonomy.classifyFamily(job.getTitle(), job.getDescription());
        for (String ex : excludedRoles) {
            if (ex == null || ex.isBlank()) continue;
            String term = ex.trim();
            if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(title).find()) {
                return true;
            }
            String exFamily = taxonomy.classifyFamily(term, null);
            if (!JobTaxonomy.FAMILY_TECH.equals(exFamily) && !JobTaxonomy.FAMILY_OTHER.equals(exFamily)
                    && exFamily.equals(jobFamily)) {
                return true;
            }
        }
        return false;
    }
}
