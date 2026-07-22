package ai.careerpilot.companyintel;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.memory.CareerMemoryBooster;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7.17 — Candidate Fit unification. These tests verify the service is pure glue: it reads
 * {@code JobScoring}'s persisted breakdown and {@code CompanyKnowledge}'s existing scores verbatim,
 * and NEVER invents a number for a dimension with no backing data source (leadership/architecture/
 * domain/cloud fit).
 */
class CandidateFitServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private JobRepository jobs;
    private JobRecommendationRepository jobRecommendations;
    private CompanyKnowledgeService companyKnowledgeService;
    private CareerMemoryBooster memoryBooster;
    private KnowledgeAggregator aggregator;

    @BeforeEach
    void setUp() {
        jobs = mock(JobRepository.class);
        jobRecommendations = mock(JobRecommendationRepository.class);
        companyKnowledgeService = mock(CompanyKnowledgeService.class);
        memoryBooster = mock(CareerMemoryBooster.class);
        aggregator = new KnowledgeAggregator();
        when(memoryBooster.computeBoost(any(), any())).thenReturn(0);
    }

    private CandidateFitService service(boolean enabled) {
        return new CandidateFitService(jobs, jobRecommendations, companyKnowledgeService, memoryBooster, aggregator, enabled);
    }

    private Job job() {
        return Job.builder().id(jobId).title("Backend Engineer").company("Acme Corp").build();
    }

    @Test
    void disabledIsANoOp() {
        assertThat(service(false).explainFit(userId, jobId)).isEmpty();
    }

    @Test
    void missingJobReturnsEmpty() {
        when(jobs.findById(jobId)).thenReturn(Optional.empty());
        assertThat(service(true).explainFit(userId, jobId)).isEmpty();
    }

    @Test
    void leadershipArchitectureDomainCloudFitAreAlwaysNullNeverFabricated() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("leadershipFit")).isNull();
        assertThat(fit.get("architectureFit")).isNull();
        assertThat(fit.get("domainFit")).isNull();
        assertThat(fit.get("cloudFit")).isNull();
        assertThat((String) fit.get("leadershipFitExplanation")).contains("not computed");
    }

    @Test
    void allDimensionsAreNullWhenNeitherSourceExists() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("skillFit")).isNull();
        assertThat(fit.get("technologyFit")).isNull();
        assertThat(fit.get("overallFit")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> sources = (Map<String, Object>) fit.get("sources");
        assertThat(sources.get("jobRecommendation")).isEqualTo(false);
        assertThat(sources.get("companyKnowledge")).isEqualTo(false);
    }

    @Test
    void jobLevelDimensionsReadVerbatimFromPersistedScoreBreakdown() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        JobRecommendation rec = JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .matchScore(72).matchingSkills("java,spring").missingSkills("kubernetes")
                .scoreBreakdown("{\"skills\":80,\"experience\":90,\"role\":70,\"location\":100,\"salary\":50,\"visa\":50,\"workMode\":50,\"learningBoost\":0}")
                .build();
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("skillFit")).isEqualTo(80);
        assertThat(fit.get("experienceFit")).isEqualTo(90);
        assertThat(fit.get("locationFit")).isEqualTo(100);
        assertThat((String) fit.get("skillFitExplanation")).contains("java,spring").contains("kubernetes");
    }

    @Test
    void companyLevelDimensionsReadVerbatimFromCompanyKnowledge() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        CompanyKnowledge ck = CompanyKnowledge.builder()
                .id(UUID.randomUUID()).userId(userId).companyName("Acme Corp").normalizedName("acme")
                .technologyMatch(65).careerGrowth(55).qualityScore(60)
                .scoreExplanations("{\"technologyMatch\":\"candidate covers 65% of observed tech\",\"careerGrowth\":\"2 roles observed\"}")
                .build();
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.of(ck));

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("technologyFit")).isEqualTo(65);
        assertThat(fit.get("careerGoalFit")).isEqualTo(55);
        assertThat((String) fit.get("technologyFitExplanation")).contains("65%");
    }

    @Test
    void overallFitAveragesBothEnginesWhenBothExist() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        JobRecommendation rec = JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(80)
                .build();
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        CompanyKnowledge ck = CompanyKnowledge.builder()
                .id(UUID.randomUUID()).userId(userId).companyName("Acme Corp").normalizedName("acme")
                .qualityScore(60).build();
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.of(ck));

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("overallFit")).isEqualTo(70); // round((80+60)/2)
    }

    @Test
    void overallFitFallsBackToWhicheverSingleSourceExists() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        JobRecommendation rec = JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(80).build();
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("overallFit")).isEqualTo(80);
    }

    @Test
    void memoryInfluenceReflectsCareerMemoryBoosterVerbatim() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());
        when(memoryBooster.computeBoost(any(), any())).thenReturn(-3);

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("memoryInfluence")).isEqualTo(-3);
        assertThat((String) fit.get("memoryInfluenceExplanation")).contains("-3");
    }

    @Test
    void neverThrowsOnMalformedScoreBreakdownJson() {
        when(jobs.findById(jobId)).thenReturn(Optional.of(job()));
        JobRecommendation rec = JobRecommendation.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId).matchScore(50)
                .scoreBreakdown("not-json-at-all")
                .build();
        when(jobRecommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        when(companyKnowledgeService.findByName(userId, "Acme Corp")).thenReturn(Optional.empty());

        Map<String, Object> fit = service(true).explainFit(userId, jobId).orElseThrow();

        assertThat(fit.get("skillFit")).isNull();
    }
}
