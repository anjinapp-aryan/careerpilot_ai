package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * International Job Discovery Engine, Phase 1 — seniority gate for the relocation program: only
 * Mid-Senior/Senior/Staff/Principal/Architect/Lead roles qualify; Intern/Graduate/Junior/
 * Trainee/Contract/Part-time do not. Reuses the already-existing
 * {@link JobTaxonomy#seniorityLevel(String)} rather than re-deriving seniority from a title, so
 * this filter and the existing scoring engine can never disagree on what "senior" means.
 */
@Component
public class SeniorityLevelClassifier {

    /** Belt-and-suspenders reject list: "Contract"/"Part-time" carry no seniority signal in the taxonomy. */
    private static final List<String> EXPLICIT_REJECT_TERMS = List.of(
            "intern", "internship", "graduate", "junior", "trainee", "contract", "part-time", "part time");

    private final JobTaxonomy taxonomy;

    public SeniorityLevelClassifier(JobTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    /**
     * True for SENIOR/LEAD/PRINCIPAL (JobTaxonomy already folds staff/principal/distinguished into
     * PRINCIPAL, and lead/architect/manager into LEAD). SENIORITY_UNKNOWN is treated as eligible —
     * no signal never rejects, matching every other filter's convention in this codebase.
     */
    public boolean isEligibleSeniority(Job job) {
        int level = taxonomy.seniorityLevel(job.getTitle());
        return level == JobTaxonomy.SENIORITY_UNKNOWN
                || level == JobTaxonomy.SENIORITY_SENIOR
                || level == JobTaxonomy.SENIORITY_LEAD
                || level == JobTaxonomy.SENIORITY_PRINCIPAL;
    }

    /** Whole-word (case-insensitive) match against the explicit reject-term list. */
    public boolean isExplicitlyRejectedTitle(Job job) {
        String title = job.getTitle();
        if (title == null || title.isBlank()) return false;
        for (String term : EXPLICIT_REJECT_TERMS) {
            if (Pattern.compile("\\b" + Pattern.quote(term) + "\\b", Pattern.CASE_INSENSITIVE)
                    .matcher(title).find()) {
                return true;
            }
        }
        return false;
    }
}
