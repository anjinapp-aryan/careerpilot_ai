package ai.careerpilot.jobdiscovery.international;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * International Job Discovery Engine, Phase 1 — {@link InternationalEligibilityFilter}. Pins the
 * two independent flags: the master seniority-filter flag (explicit-reject titles + seniority
 * level) and the separately-toggleable title allow-list sub-gate.
 */
class InternationalEligibilityFilterTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final SeniorityLevelClassifier seniority = new SeniorityLevelClassifier(taxonomy);
    private final InternationalRoleTaxonomy roleTaxonomy = new InternationalRoleTaxonomy(taxonomy);

    private static Job job(String title) {
        return Job.builder().title(title).description("").build();
    }

    private InternationalEligibilityFilter filter(boolean enabled, boolean requireAllowedTitle) {
        return new InternationalEligibilityFilter(seniority, roleTaxonomy, enabled, requireAllowedTitle);
    }

    @Test
    void flagOffAlwaysEligible() {
        InternationalEligibilityFilter f = filter(false, false);
        assertTrue(f.isEligible(job("Intern Software Engineer")));
        assertTrue(f.isEligible(job("Junior Developer")));
    }

    @Test
    void explicitlyRejectedTitlesAreRejectedWhenEnabled() {
        InternationalEligibilityFilter f = filter(true, false);
        assertFalse(f.isEligible(job("Software Engineering Intern")));
        assertFalse(f.isEligible(job("Junior Backend Developer")));
        assertFalse(f.isEligible(job("Graduate Software Engineer")));
        assertFalse(f.isEligible(job("Engineering Trainee")));
        assertFalse(f.isEligible(job("Backend Engineer (Contract)")));
        assertFalse(f.isEligible(job("Part-time Frontend Developer")));
    }

    @Test
    void seniorStaffPrincipalLeadArchitectAreEligibleWhenEnabled() {
        InternationalEligibilityFilter f = filter(true, false);
        assertTrue(f.isEligible(job("Senior Software Engineer")));
        assertTrue(f.isEligible(job("Staff Software Engineer")));
        assertTrue(f.isEligible(job("Principal Software Engineer")));
        assertTrue(f.isEligible(job("Technical Lead")));
        assertTrue(f.isEligible(job("Software Architect")));
    }

    @Test
    void unknownSeniorityIsEligible() {
        // No seniority-carrying token at all — no signal never rejects.
        InternationalEligibilityFilter f = filter(true, false);
        assertTrue(f.isEligible(job("Backend Engineer")));
    }

    @Test
    void titleAllowlistSubGateRejectsOffListTitlesEvenAtEligibleSeniority() {
        InternationalEligibilityFilter f = filter(true, true);
        // "Senior QA Engineer" is senior-level but not one of the 27 allow-listed titles/families.
        assertFalse(f.isEligible(job("Senior QA Automation Engineer")));
        assertTrue(f.isEligible(job("Senior Software Engineer")));
    }

    @Test
    void titleAllowlistSubGateOffLeavesOnlySeniorityCheck() {
        InternationalEligibilityFilter f = filter(true, false);
        assertTrue(f.isEligible(job("Senior QA Automation Engineer")));
    }
}
