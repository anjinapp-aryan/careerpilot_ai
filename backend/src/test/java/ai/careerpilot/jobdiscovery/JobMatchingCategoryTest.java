package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Phase 2B-1 — the matcher stamps {@code category} on each persisted recommendation only when
 * {@code JOB_AUTO_CATEGORIZATION_ENABLED} is on, and the stamped value matches the categorizer's
 * pure mapping of the recommendation's own score. Flag off → category stays null (zero change).
 */
class JobMatchingCategoryTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final UUID userId = UUID.randomUUID();

    private CandidateMatchSignals signals() {
        return new CandidateMatchSignals(
                List.of("Java", "Spring Boot", "Kubernetes", "AWS"), "Backend Engineer",
                List.of(), 8, null, JobScoring.PreferenceContext.empty(), List.of(), null, "PROFILE");
    }

    private Job job() {
        return Job.builder().id(UUID.randomUUID())
                .title("Senior Backend Engineer").company("Acme")
                .description("Java Spring Boot Kubernetes AWS").jobFamily("ENGINEERING")
                .build();
    }

    private JobMatchingService matcher(JobRecommendationRepository recommendations, boolean categorize) {
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        JobRepository jobs = mock(JobRepository.class);
        when(resolver.resolve(userId)).thenReturn(Optional.of(signals()));
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        return new JobMatchingService(resolver, jobs, recommendations, new JobScoring(taxonomy), taxonomy,
                new RoleExclusionFilter(taxonomy), mock(CandidateProfileVersionRepository.class),
                mock(RecommendationAuditRepository.class), mock(JobAiEnrichmentRepository.class),
                new JobCategorizer(categorize), new PreferenceGate(new JobScoring(taxonomy)),
                new MatchCache(mock(StringRedisTemplate.class), new MatchCacheMetrics(), false),
                new ai.careerpilot.jobdiscovery.priority.PriorityEngine(false), new MustApplyEvaluator(),
                mock(ai.careerpilot.learning.recommendation.LearningRecommendationBooster.class),
                mock(ai.careerpilot.companyintel.CompanyKnowledgeBooster.class),
                mock(ai.careerpilot.memory.CareerMemoryBooster.class),
                // Phase 13C — production-evidence booster disabled, so these suites keep asserting
                // exactly the scores they did before. Its behaviour is covered by its own suite.
                new ai.careerpilot.intelligence.ProductionIntelligenceBooster(false),
                mock(ai.careerpilot.intelligence.ProductionIntelligenceService.class),
                new InternationalEligibilityFilter(new SeniorityLevelClassifier(taxonomy), new InternationalRoleTaxonomy(taxonomy), false, false),
                false, 0, 0, false, false, false, 0, false);   // strict gate off so the seeded job always persists
    }

    @Test
    void categorizationEnabledStampsCategoryMatchingTheScore() {
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        matcher(recommendations, true).refreshForUser(userId);

        ArgumentCaptor<JobRecommendation> captor = ArgumentCaptor.forClass(JobRecommendation.class);
        verify(recommendations).save(captor.capture());
        JobRecommendation saved = captor.getValue();

        assertNotNull(saved.getCategory());
        assertEquals(new JobCategorizer(true).categorize(saved.getMatchScore()).name(), saved.getCategory());
    }

    @Test
    void categorizationDisabledLeavesCategoryNull() {
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        matcher(recommendations, false).refreshForUser(userId);

        ArgumentCaptor<JobRecommendation> captor = ArgumentCaptor.forClass(JobRecommendation.class);
        verify(recommendations).save(captor.capture());
        assertNull(captor.getValue().getCategory());
    }
}
