package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Phase 3B.1 — domain/industry preference filter. Reuses the candidate's canonical
 * {@code CandidateProfile.domains}/{@code industries} (already populated by resume AI extraction)
 * as "preferred domains" — no new persisted preference fields or migration needed. Excluded
 * domains are a fixed, configurable keyword list checked against the job's title/description,
 * extending {@link JobTaxonomy#isExcludedFamily} (which already excludes Marketing/Sales/HR/
 * Recruiter/Support/Finance) with the additional Phase 3B.1 categories (Media, Hospitality,
 * Construction, Creative, BIM) that aren't part of that taxonomy's family set.
 */
@Component
public class DomainPreferenceService {

    private final JobTaxonomy taxonomy;
    private final List<String> excludedKeywords;

    public DomainPreferenceService(
            JobTaxonomy taxonomy,
            @Value("${career.relevance.excluded-domains:"
                    + "Media,Hospitality,Customer Service,Construction,Sales,Marketing,HR,Creative,BIM}")
            String excludedDomainsCsv) {
        this.taxonomy = taxonomy;
        this.excludedKeywords = splitLower(excludedDomainsCsv);
    }

    /** True when the job falls into an excluded domain (via family classification or keyword). */
    public boolean isExcluded(Job job) {
        String family = taxonomy.classifyFamily(job.getTitle(), job.getDescription());
        if (taxonomy.isExcludedFamily(family)) return true;

        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())).toLowerCase(Locale.ROOT);
        for (String kw : excludedKeywords) {
            if (!kw.isBlank() && haystack.contains(kw)) return true;
        }
        return false;
    }

    /**
     * True when the job aligns with the candidate's preferred domains/industries, or when the
     * candidate has none on file (no signal → neutral pass, never a false rejection).
     */
    public boolean fitsPreferredDomains(Job job, List<String> preferredDomains) {
        if (isExcluded(job)) return false;
        if (preferredDomains == null || preferredDomains.isEmpty()) return true;

        String haystack = ((job.getTitle() == null ? "" : job.getTitle()) + " "
                + (job.getDescription() == null ? "" : job.getDescription())).toLowerCase(Locale.ROOT);
        for (String domain : preferredDomains) {
            if (domain != null && !domain.isBlank() && haystack.contains(domain.toLowerCase(Locale.ROOT).trim())) {
                return true;
            }
        }
        // Tech family jobs are never penalized purely for not naming a preferred domain verbatim —
        // only explicit exclusion (above) removes a job; an unmatched preference is still a pass.
        return true;
    }

    private static List<String> splitLower(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .toList();
    }
}
