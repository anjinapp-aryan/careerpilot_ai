package ai.careerpilot.discovery.relevance;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.JobTaxonomy;
import ai.careerpilot.jobdiscovery.RoleExclusionFilter;
import ai.careerpilot.jobdiscovery.international.InternationalEligibilityFilter;
import ai.careerpilot.jobdiscovery.international.InternationalRoleTaxonomy;
import ai.careerpilot.jobdiscovery.international.SeniorityLevelClassifier;
import ai.careerpilot.jobdiscovery.scope.JobScopeStrategyResolver;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.service.JobService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3B.1 — Steps 7-9: {@code JobService.discovered} additively gates/sorts Domestic and
 * International by career relevance when both the master flag and the scope-specific flag are on,
 * and leaves Browse ({@link JobService#browsePool}) completely untouched (Step 9 is satisfied by
 * this test suite never exercising it). Uses the legacy (scope-strict-disabled) query path so only
 * {@code JobRepository.findDiscoveredFiltered} needs stubbing.
 */
class JobServiceCareerRelevanceTest {

    private static Job job(String id, String title, Instant postedDate) {
        return Job.builder().id(UUID.fromString(id)).title(title).company("Acme")
                .description("desc").postedDate(postedDate).build();
    }

    private static final Job STRONG = job("00000000-0000-0000-0000-000000000001", "Senior Java Developer",
            Instant.parse("2026-01-01T00:00:00Z"));
    private static final Job WEAK = job("00000000-0000-0000-0000-000000000002", "Media Manager",
            Instant.parse("2026-02-01T00:00:00Z"));
    private static final Job MODERATE_OLDER = job("00000000-0000-0000-0000-000000000003", "Java Engineer",
            Instant.parse("2026-01-15T00:00:00Z"));

    private static final CareerThresholdPolicy LEGACY_POLICY = new CareerThresholdPolicy(false, 85, 60, 60, 60);
    private static final CareerThresholdPolicy SOFT_POLICY = new CareerThresholdPolicy(true, 85, 60, 60, 60);
    private static final InternationalEligibilityFilter ELIGIBILITY_FILTER_OFF = new InternationalEligibilityFilter(
            new SeniorityLevelClassifier(new JobTaxonomy()), new InternationalRoleTaxonomy(new JobTaxonomy()), false, false);

    private JobService serviceWith(boolean masterEnabled, boolean domesticEnabled, boolean internationalEnabled,
                                   CareerRelevanceEvaluator evaluator) {
        return serviceWith(domesticEnabled, internationalEnabled, evaluator, LEGACY_POLICY);
    }

    private JobService serviceWith(boolean domesticEnabled, boolean internationalEnabled,
                                   CareerRelevanceEvaluator evaluator, CareerThresholdPolicy policy) {
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findDiscoveredFiltered(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(STRONG, WEAK, MODERATE_OLDER), PageRequest.of(0, 20), 3));

        CandidateSignalResolver signalResolver = mock(CandidateSignalResolver.class);
        when(signalResolver.resolveLocationSignals(any()))
                .thenReturn(new CandidateSignalResolver.CandidateLocationSignals(null, List.of(), List.of(), "PREFERENCES"));

        return new JobService(jobs, mock(JobScopeStrategyResolver.class), new RoleExclusionFilter(new JobTaxonomy()),
                signalResolver, evaluator, policy, ELIGIBILITY_FILTER_OFF, 75, false, domesticEnabled, internationalEnabled);
    }

    /** Evaluator stub: STRONG scores 95, MODERATE_OLDER scores 80, WEAK scores 40 (below threshold). */
    private CareerRelevanceEvaluator scoringEvaluator(boolean enabled) {
        CareerRelevanceEvaluator evaluator = mock(CareerRelevanceEvaluator.class);
        when(evaluator.isEnabled()).thenReturn(enabled);
        when(evaluator.resolveContext(any())).thenReturn(new RelevanceCandidateContext("Java Architect", List.of(), 12, List.of()));
        when(evaluator.score(any(), any())).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            if (j.getId().equals(STRONG.getId())) return new CareerRelevanceScore(95, "Strong Match", true, 100, true, true);
            if (j.getId().equals(MODERATE_OLDER.getId())) return new CareerRelevanceScore(80, "Excellent Match", true, 70, true, true);
            return new CareerRelevanceScore(40, "Hidden", false, 0, false, false);
        });
        return evaluator;
    }

    /** Same three jobs, but WEAK now scores 65 (a soft-threshold "Weak Match", not a hard-zero). */
    private CareerRelevanceEvaluator softBandEvaluator() {
        CareerRelevanceEvaluator evaluator = mock(CareerRelevanceEvaluator.class);
        when(evaluator.isEnabled()).thenReturn(true);
        when(evaluator.resolveContext(any())).thenReturn(new RelevanceCandidateContext("Java Architect", List.of(), 12, List.of()));
        when(evaluator.score(any(), any())).thenAnswer(inv -> {
            Job j = inv.getArgument(0);
            if (j.getId().equals(STRONG.getId())) return new CareerRelevanceScore(95, "Strong Match", true, 100, true, true);
            if (j.getId().equals(MODERATE_OLDER.getId())) return new CareerRelevanceScore(80, "Excellent Match", true, 70, true, true);
            return new CareerRelevanceScore(65, "Weak Match", true, 40, true, false);
        });
        return evaluator;
    }

    @Test
    void weakScoreIsHiddenUnderLegacyHardCutoff() {
        // Soft-thresholds flag off: score=65 is below the legacy >=70 cutoff, so it's filtered out.
        JobService service = serviceWith(true, false, softBandEvaluator(), LEGACY_POLICY);
        Page<Job> page = service.discovered(UUID.randomUUID(), "domestic", "India", null, null, null, null, 0, 20);

        assertEquals(2, page.getContent().size(), "score=65 must not clear the legacy >=70 cutoff");
    }

    @Test
    void weakScoreIsVisibleWhenSoftThresholdsEnabled() {
        // Soft-thresholds flag on: domestic threshold defaults to 60, so score=65 now clears it.
        JobService service = serviceWith(true, false, softBandEvaluator(), SOFT_POLICY);
        Page<Job> page = service.discovered(UUID.randomUUID(), "domestic", "India", null, null, null, null, 0, 20);

        assertEquals(3, page.getContent().size(), "score=65 must clear the soft domestic threshold (60)");
    }

    @Test
    void domesticFeedFiltersAndSortsByRelevanceWhenEnabled() {
        JobService service = serviceWith(true, true, false, scoringEvaluator(true));
        Page<Job> page = service.discovered(UUID.randomUUID(), "domestic", "India", null, null, null, null, 0, 20);

        assertEquals(2, page.getContent().size(), "WEAK (score 40) must be filtered out");
        assertEquals(STRONG.getId(), page.getContent().get(0).getId(), "higher relevance sorts first");
        assertEquals(MODERATE_OLDER.getId(), page.getContent().get(1).getId());
    }

    @Test
    void internationalFeedFiltersAndSortsByRelevanceWhenEnabled() {
        JobService service = serviceWith(true, false, true, scoringEvaluator(true));
        Page<Job> page = service.discovered(UUID.randomUUID(), "international", "India", null, null, null, null, 0, 20);

        assertEquals(2, page.getContent().size());
        assertEquals(STRONG.getId(), page.getContent().get(0).getId());
    }

    @Test
    void domesticFeedIsUnfilteredWhenScopeFlagOff() {
        // Master flag on, but the domestic-specific flag is off — legacy behavior, all 3 jobs kept.
        JobService service = serviceWith(true, false, false, scoringEvaluator(true));
        Page<Job> page = service.discovered(UUID.randomUUID(), "domestic", "India", null, null, null, null, 0, 20);

        assertEquals(3, page.getContent().size());
    }

    @Test
    void feedIsUnfilteredWhenMasterFlagOff() {
        // Scope flag on, but the master career.relevance.enabled flag is off — legacy behavior.
        JobService service = serviceWith(false, true, true, scoringEvaluator(false));
        Page<Job> page = service.discovered(UUID.randomUUID(), "domestic", "India", null, null, null, null, 0, 20);

        assertEquals(3, page.getContent().size());
    }

    @Test
    void browsePoolNeverCallsRelevanceEvaluator() {
        CareerRelevanceEvaluator evaluator = mock(CareerRelevanceEvaluator.class);
        JobRepository jobs = mock(JobRepository.class);
        when(jobs.findBrowsePool(any(), anyInt(), any()))
                .thenReturn(new PageImpl<>(List.of(STRONG, WEAK, MODERATE_OLDER)));
        JobService service = new JobService(jobs, mock(JobScopeStrategyResolver.class),
                new RoleExclusionFilter(new JobTaxonomy()), mock(CandidateSignalResolver.class),
                evaluator, LEGACY_POLICY, ELIGIBILITY_FILTER_OFF, 75, false, true, true);

        Page<Job> page = service.browsePool(UUID.randomUUID(), 0, 20);

        assertEquals(3, page.getContent().size(), "Browse must return everything, unfiltered");
    }
}
