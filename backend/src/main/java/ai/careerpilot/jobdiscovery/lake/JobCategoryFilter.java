package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Software-only pre-store gate for the Job Lake (Phase 2A Step 8). Reuses the deterministic
 * {@link JobTaxonomy#classifyFamily} the recommendation engine already trusts, so the lake can never
 * disagree with Recommended about what "is" a software role. Keeps only {@code TECH}; drops
 * MARKETING/SALES/HR/SUPPORT/FINANCE/etc.
 *
 * <p>Gated by {@code JOB_SOFTWARE_FILTER_ENABLED} (default false). When off, every job is kept —
 * matching Step 9's "store everything" — so flipping it on is the only thing that narrows the lake.
 */
@Component
public class JobCategoryFilter {

    private static final Logger log = LoggerFactory.getLogger(JobCategoryFilter.class);

    private final JobTaxonomy taxonomy;
    private final boolean enabled;

    public JobCategoryFilter(JobTaxonomy taxonomy,
                             @Value("${jobs.discovery.lake.software-filter-enabled:false}") boolean enabled) {
        this.taxonomy = taxonomy;
        this.enabled = enabled;
    }

    /** True when this job should enter the lake. Flag off → always true (store everything). */
    public boolean isSoftwareJob(String title, String description) {
        if (!enabled) return true;
        String family = taxonomy.classifyFamily(title, description);
        boolean keep = JobTaxonomy.FAMILY_TECH.equals(family);
        if (!keep) log.debug("lake software-filter dropped non-tech job: family={} title={}", family, title);
        return keep;
    }
}
