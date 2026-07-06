package ai.careerpilot.dailydiscovery;

import ai.careerpilot.discovery.relevance.CareerMatchStrength;
import ai.careerpilot.discovery.relevance.CareerRelevanceEvaluator;
import ai.careerpilot.discovery.relevance.CareerRelevanceResult;
import ai.careerpilot.discovery.relevance.RelevanceCandidateContext;
import ai.careerpilot.domain.DailyDiscoveryAnalytics;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver;
import ai.careerpilot.jobdiscovery.JobMatchingService;
import ai.careerpilot.repo.DailyDiscoveryAnalyticsRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Per-user classification/aggregation logic — the part {@link DailyJobDiscoveryService} owns on
 * top of the reused {@link JobMatchingService}. Verifies category/mustApply counting, home-country
 * domestic/international bucketing, and that a relevance-disabled evaluator leaves hidden/strength
 * distributions empty (dark-tolerant) rather than throwing.
 */
class DailyJobDiscoveryServiceTest {

    private JobMatchingService matching;
    private JobRecommendationRepository recommendations;
    private JobRepository jobs;
    private CareerRelevanceEvaluator relevanceEvaluator;
    private CandidateSignalResolver signalResolver;
    private DailyDiscoveryAnalyticsRepository analyticsRepo;
    private DailyJobDiscoveryService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        matching = mock(JobMatchingService.class);
        recommendations = mock(JobRecommendationRepository.class);
        jobs = mock(JobRepository.class);
        relevanceEvaluator = mock(CareerRelevanceEvaluator.class);
        signalResolver = mock(CandidateSignalResolver.class);
        analyticsRepo = mock(DailyDiscoveryAnalyticsRepository.class);
        service = new DailyJobDiscoveryService(matching, recommendations, jobs, relevanceEvaluator, signalResolver, analyticsRepo);

        when(signalResolver.resolveLocationSignals(userId))
                .thenReturn(new CandidateSignalResolver.CandidateLocationSignals("India", List.of(), List.of(), "PROFILE"));
    }

    @Test
    void emptyRecommendationsProduceZeroedSnapshotAndNoPersist() {
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());

        var snapshot = service.processUser(runId, userId);

        assertEquals(0, snapshot.recommendedJobs());
        assertEquals(0, snapshot.mustApplyJobs());
        verify(matching).refreshForUser(userId);
        verify(analyticsRepo).save(any());
    }

    @Test
    void classifiesCategoryMustApplyAndDomesticVsInternational() {
        UUID jobDomestic = UUID.randomUUID();
        UUID jobIntl = UUID.randomUUID();

        JobRecommendation r1 = JobRecommendation.builder().userId(userId).jobId(jobDomestic)
                .matchScore(96).category("HIGH_PRIORITY").mustApply(true).matchingSkills("java,spring").build();
        JobRecommendation r2 = JobRecommendation.builder().userId(userId).jobId(jobIntl)
                .matchScore(82).category("HUMAN_REVIEW").mustApply(false).matchingSkills("java").build();

        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(r1, r2));

        Job domesticJob = Job.builder().id(jobDomestic).title("SWE").company("Acme").country("India").jobFamily("TECH").build();
        Job intlJob = Job.builder().id(jobIntl).title("SWE").company("Globex").country("Germany").jobFamily("TECH").build();
        when(jobs.findAllById(anyList())).thenReturn(List.of(domesticJob, intlJob));

        when(relevanceEvaluator.isEnabled()).thenReturn(false);

        var snapshot = service.processUser(runId, userId);

        assertEquals(2, snapshot.recommendedJobs());
        assertEquals(1, snapshot.mustApplyJobs());
        assertEquals(1, snapshot.highPriorityJobs());
        assertEquals(1, snapshot.humanReviewJobs());
        assertEquals(1, snapshot.domesticJobs());
        assertEquals(1, snapshot.internationalJobs());
        assertEquals(0, snapshot.hiddenJobs()); // relevance disabled -> never marked hidden
        assertTrue(snapshot.companyDistribution().containsKey("Acme"));
        assertTrue(snapshot.skillDistribution().containsKey("java"));

        var captor = org.mockito.ArgumentCaptor.forClass(DailyDiscoveryAnalytics.class);
        verify(analyticsRepo).save(captor.capture());
        assertEquals(runId, captor.getValue().getRunId());
        assertEquals(userId, captor.getValue().getUserId());
    }

    @Test
    void relevanceEnabledCountsHiddenAndStrength() {
        UUID jobId = UUID.randomUUID();
        JobRecommendation r = JobRecommendation.builder().userId(userId).jobId(jobId).matchScore(75).build();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(r));

        Job job = Job.builder().id(jobId).title("SWE").company("Acme").country("India").jobFamily("TECH").build();
        when(jobs.findAllById(anyList())).thenReturn(List.of(job));

        when(relevanceEvaluator.isEnabled()).thenReturn(true);
        RelevanceCandidateContext ctx = mock(RelevanceCandidateContext.class);
        when(relevanceEvaluator.resolveContext(userId)).thenReturn(ctx);
        when(relevanceEvaluator.evaluateForScope(eq(job), eq(ctx), eq("domestic")))
                .thenReturn(new CareerRelevanceResult(55, CareerMatchStrength.WEAK, false, List.of("low overlap")));

        var snapshot = service.processUser(runId, userId);

        assertEquals(1, snapshot.hiddenJobs());
        assertEquals(1, snapshot.matchStrengthDistribution().get("WEAK"));
    }
}
