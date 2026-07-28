package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.jobdiscovery.cache.MatchCache;
import ai.careerpilot.jobdiscovery.cache.MatchCacheMetrics;
import ai.careerpilot.jobdiscovery.international.InternationalEligibilityFilter;
import ai.careerpilot.jobdiscovery.international.InternationalRoleTaxonomy;
import ai.careerpilot.jobdiscovery.international.SeniorityLevelClassifier;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.RecommendationAuditRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Phase 2B-5 — end-to-end through {@code refreshForUser}: a "Remote only" candidate must never get
 * an onsite job persisted as a recommendation once the flag is on, and must see it (soft-scored,
 * lower-ranked) when the flag is off — pinning that this is a pure additive filter, not a rescoring
 * change.
 */
class JobMatchingHardPreferenceTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final UUID userId = UUID.randomUUID();

    private CandidateMatchSignals remoteOnlySignals() {
        JobScoring.PreferenceContext remoteOnly = new JobScoring.PreferenceContext(
                List.of(), List.of(), true, false, false, false, null, null);
        return new CandidateMatchSignals(
                List.of("Java", "Spring Boot", "Kubernetes", "AWS"), "Backend Architect",
                List.of(), 10, null, remoteOnly, List.of(), null, "PROFILE");
    }

    private Job onsiteJob() {
        return Job.builder().id(UUID.randomUUID())
                .title("Backend Architect").company("Acme").remoteType("ONSITE").country("Germany")
                .description("Java Spring Boot Kubernetes AWS").jobFamily("ENGINEERING")
                .build();
    }

    private JobMatchingService matcher(JobRecommendationRepository recommendations, boolean hardPreference) {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        JobRepository jobs = mock(JobRepository.class);
        when(resolver.resolve(userId)).thenReturn(Optional.of(remoteOnlySignals()));
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(onsiteJob()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        return new JobMatchingService(resolver, jobs, recommendations, new JobScoring(taxonomy), taxonomy,
                new RoleExclusionFilter(taxonomy), mock(CandidateProfileVersionRepository.class),
                mock(RecommendationAuditRepository.class), mock(JobAiEnrichmentRepository.class),
                new JobCategorizer(false), new PreferenceGate(new JobScoring(taxonomy)),
                new MatchCache(mock(StringRedisTemplate.class), new MatchCacheMetrics(), false),
                new ai.careerpilot.jobdiscovery.priority.PriorityEngine(false), new MustApplyEvaluator(),
                mock(ai.careerpilot.learning.recommendation.LearningRecommendationBooster.class),
                mock(ai.careerpilot.companyintel.CompanyKnowledgeBooster.class),
                mock(ai.careerpilot.memory.CareerMemoryBooster.class),
                new InternationalEligibilityFilter(new SeniorityLevelClassifier(taxonomy), new InternationalRoleTaxonomy(taxonomy), false, false),
                false, 0, 0, false, false, false, 0, hardPreference);   // strict gate off so scoring alone wouldn't drop the job
    }

    @Test
    void hardPreferenceEnabledDropsTheOnsiteJobForARemoteOnlyCandidate() {
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        int written = matcher(recommendations, true).refreshForUser(userId);

        assertEquals(0, written);
        verify(recommendations, never()).save(any());
    }

    @Test
    void hardPreferenceDisabledStillPersistsTheOnsiteJobSoftScored() {
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        int written = matcher(recommendations, false).refreshForUser(userId);

        assertEquals(1, written);
        verify(recommendations, times(1)).save(any());
    }
}
