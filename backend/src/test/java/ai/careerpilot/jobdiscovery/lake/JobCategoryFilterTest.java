package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The software-only pre-store gate. Flag off → everything is kept (Step 9 "store everything");
 * flag on → only TECH roles pass, reusing the same {@link JobTaxonomy} the recommender trusts.
 */
class JobCategoryFilterTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();

    @Test
    void disabledKeepsEverythingIncludingNonTech() {
        JobCategoryFilter filter = new JobCategoryFilter(taxonomy, false);
        assertTrue(filter.isSoftwareJob("Marketing Manager", "run campaigns"));
        assertTrue(filter.isSoftwareJob("Senior Backend Engineer", "java spring"));
        assertTrue(filter.isSoftwareJob(null, null));
    }

    @Test
    void enabledKeepsTechRoles() {
        JobCategoryFilter filter = new JobCategoryFilter(taxonomy, true);
        assertTrue(filter.isSoftwareJob("Senior Backend Engineer", "java, spring, kubernetes"));
        assertTrue(filter.isSoftwareJob("Platform Engineer", "aws terraform"));
        assertTrue(filter.isSoftwareJob("Solution Architect", "designing distributed systems"));
    }

    @Test
    void enabledDropsNonTechRoles() {
        JobCategoryFilter filter = new JobCategoryFilter(taxonomy, true);
        assertFalse(filter.isSoftwareJob("Marketing Manager", "brand campaigns and seo"));
        assertFalse(filter.isSoftwareJob("Account Executive", "sales quota pipeline"));
        assertFalse(filter.isSoftwareJob("HR Business Partner", "people operations"));
    }
}
