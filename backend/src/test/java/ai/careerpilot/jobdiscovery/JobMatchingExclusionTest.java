package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * The user-defined excluded-roles filter. The headline risk is over-filtering, so these tests pin
 * the two guards: whole-word title matching (no "Salesforce" false positive from "Sales") and
 * non-tech family matching (excluding "Marketing" drops the MARKETING family even without a title
 * hit). Uses the real {@link JobTaxonomy}; the matcher's other collaborators are mocked.
 */
class JobMatchingExclusionTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final JobMatchingService matcher = new JobMatchingService(
            mock(CandidateSignalResolver.class), mock(JobRepository.class),
            mock(JobRecommendationRepository.class), new JobScoring(taxonomy), taxonomy,
            new RoleExclusionFilter(taxonomy),
            mock(CandidateProfileVersionRepository.class), mock(RecommendationAuditRepository.class),
            mock(JobAiEnrichmentRepository.class),
            true, 70, 3, true, false, false, 40);

    private static Job job(String title, String description) {
        return Job.builder().title(title).description(description).build();
    }

    @Test
    void emptyExclusionsNeverFilter() {
        assertFalse(matcher.isRoleExcluded(job("Sales Executive", ""), List.of()));
        assertFalse(matcher.isRoleExcluded(job("Sales Executive", ""), null));
    }

    @Test
    void excludesWholeWordTitleMatch() {
        assertTrue(matcher.isRoleExcluded(job("Sales Executive", ""), List.of("Sales")));
        assertTrue(matcher.isRoleExcluded(job("Customer Support Agent", ""), List.of("Support")));
    }

    @Test
    void doesNotFalsePositiveOnSubstring() {
        // "Salesforce Engineer" is a TECH role and must NOT be dropped by an excluded "Sales".
        assertFalse(matcher.isRoleExcluded(job("Salesforce Engineer", "java spring"), List.of("Sales")));
    }

    @Test
    void excludesByNonTechFamilyWithoutTitleHit() {
        // "Brand Manager" has no "marketing" token but classifies as the MARKETING family.
        assertTrue(matcher.isRoleExcluded(job("Brand Manager", ""), List.of("Marketing")));
    }

    @Test
    void keepsTechRoleWhenExcludedTermIsTechnical() {
        // Excluding "Backend" must not nuke unrelated tech roles by family (Backend → TECH family).
        assertFalse(matcher.isRoleExcluded(job("Java Developer", "spring boot"), List.of("Backend")));
        // ...but it still drops a job that literally says "Backend" in the title (explicit intent).
        assertTrue(matcher.isRoleExcluded(job("Backend Engineer", ""), List.of("Backend")));
    }

    @Test
    void blankExcludedEntriesAreIgnored() {
        assertFalse(matcher.isRoleExcluded(job("Sales Executive", ""), List.of("  ", "")));
    }
}
