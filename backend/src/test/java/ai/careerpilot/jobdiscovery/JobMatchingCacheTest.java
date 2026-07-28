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
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 2B-3 end-to-end through {@code refreshForUser}: a second refresh with an unchanged
 * resume/pool version must be a pure cache hit — zero pool queries, zero writes — while an
 * unchanged pool but a *different* resume version must still trigger a real rescore.
 */
class JobMatchingCacheTest {

    private final JobTaxonomy taxonomy = new JobTaxonomy();
    private final UUID userId = UUID.randomUUID();
    private final Instant poolVersion = Instant.parse("2026-01-01T00:00:00Z");

    private CandidateMatchSignals signals(UUID resumeId) {
        return new CandidateMatchSignals(
                List.of("Java", "Spring Boot", "Kubernetes", "AWS"), "Backend Engineer",
                List.of(), 8, null, JobScoring.PreferenceContext.empty(), List.of(), resumeId, "PROFILE");
    }

    private Job job() {
        return Job.builder().id(UUID.randomUUID())
                .title("Senior Backend Engineer").company("Acme")
                .description("Java Spring Boot Kubernetes AWS").jobFamily("ENGINEERING")
                .build();
    }

    private MatchCache realCache() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        String[] stored = new String[1];
        doAnswer(inv -> { stored[0] = inv.getArgument(1); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> stored[0]);
        return new MatchCache(redis, new MatchCacheMetrics(), true);
    }

    private JobMatchingService matcher(JobRepository jobs, JobRecommendationRepository recommendations,
                                       CandidateSignalResolver resolver, MatchCache cache) {
        return new JobMatchingService(resolver, jobs, recommendations, new JobScoring(taxonomy), taxonomy,
                new RoleExclusionFilter(taxonomy), mock(CandidateProfileVersionRepository.class),
                mock(RecommendationAuditRepository.class), mock(JobAiEnrichmentRepository.class),
                new JobCategorizer(false), new PreferenceGate(new JobScoring(taxonomy)), cache,
                new ai.careerpilot.jobdiscovery.priority.PriorityEngine(false), new MustApplyEvaluator(),
                mock(ai.careerpilot.learning.recommendation.LearningRecommendationBooster.class),
                mock(ai.careerpilot.companyintel.CompanyKnowledgeBooster.class),
                mock(ai.careerpilot.memory.CareerMemoryBooster.class),
                new InternationalEligibilityFilter(new SeniorityLevelClassifier(taxonomy), new InternationalRoleTaxonomy(taxonomy), false, false),
                false, 0, 0, false, false, false, 0, false);   // strict gate off so the seeded job always persists
    }

    @Test
    void secondRefreshWithSameVersionsIsACacheHitAndSkipsThePoolQuery() {
        JobRepository jobs = mock(JobRepository.class);
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        UUID resumeId = UUID.randomUUID();

        when(resolver.resolve(userId)).thenReturn(Optional.of(signals(resumeId)));
        when(jobs.maxDiscoveredCreatedAt()).thenReturn(poolVersion);
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        JobMatchingService matcher = matcher(jobs, recommendations, resolver, realCache());

        int first = matcher.refreshForUser(userId);
        int second = matcher.refreshForUser(userId);

        assertEquals(1, first);
        assertEquals(0, second); // cache hit: nothing changed, nothing (re)written
        verify(jobs, times(1)).findDiscoveredPool(anyInt()); // pool queried only on the first (miss) call
        verify(recommendations, times(1)).save(any());
    }

    @Test
    void differentResumeVersionForcesARealRescoreEvenWithSamePool() {
        JobRepository jobs = mock(JobRepository.class);
        JobRecommendationRepository recommendations = mock(JobRecommendationRepository.class);
        CandidateSignalResolver resolver = mock(CandidateSignalResolver.class);
        UUID resumeV1 = UUID.randomUUID();
        UUID resumeV2 = UUID.randomUUID();

        when(jobs.maxDiscoveredCreatedAt()).thenReturn(poolVersion);
        when(jobs.findDiscoveredPool(anyInt())).thenReturn(List.of(job()));
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        JobMatchingService matcher = matcher(jobs, recommendations, resolver, realCache());

        when(resolver.resolve(userId)).thenReturn(Optional.of(signals(resumeV1)));
        matcher.refreshForUser(userId);

        when(resolver.resolve(userId)).thenReturn(Optional.of(signals(resumeV2)));
        int second = matcher.refreshForUser(userId);

        assertEquals(1, second); // resume changed → cache miss → real rescore, real write
        verify(jobs, times(2)).findDiscoveredPool(anyInt());
    }
}
