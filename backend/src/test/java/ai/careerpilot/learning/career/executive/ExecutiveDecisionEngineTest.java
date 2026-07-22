package ai.careerpilot.learning.career.executive;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.learning.career.goal.PromotionReadinessService;
import ai.careerpilot.learning.career.goal.SkillGapIntelligenceService;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 7.19.5 — verifies the engine ONLY orchestrates existing services (never computes its own
 * scores) and honestly omits decision types it has no evidence for.
 */
class ExecutiveDecisionEngineTest {

    private final UUID userId = UUID.randomUUID();
    private SkillGapIntelligenceService skillGap;
    private PromotionReadinessService promotionReadiness;
    private JobRecommendationRepository jobRecommendations;
    private JobRepository jobs;
    private InterviewRepository interviews;
    private CareerStrategyRepository strategies;

    @BeforeEach
    void setUp() {
        skillGap = mock(SkillGapIntelligenceService.class);
        promotionReadiness = mock(PromotionReadinessService.class);
        jobRecommendations = mock(JobRecommendationRepository.class);
        jobs = mock(JobRepository.class);
        interviews = mock(InterviewRepository.class);
        strategies = mock(CareerStrategyRepository.class);

        when(skillGap.isEnabled()).thenReturn(false);
        when(promotionReadiness.isEnabled()).thenReturn(false);
        when(jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of());
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(strategies.findByUserId(userId)).thenReturn(Optional.empty());
    }

    private ExecutiveDecisionEngine engine(boolean enabled) {
        return new ExecutiveDecisionEngine(skillGap, promotionReadiness, jobRecommendations, jobs, interviews, strategies, enabled);
    }

    @Test
    void disabledReturnsEmptyMap() {
        assertThat(engine(false).decide(userId)).isEmpty();
    }

    @Test
    void allDependenciesDisabledOmitsEveryDecisionTypeHonestly() {
        Map<String, Object> result = engine(true).decide(userId);

        assertThat(result.get("decisions")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<String> omitted = (List<String>) result.get("omittedDecisionTypes");
        assertThat(omitted).anyMatch(s -> s.startsWith("APPLY_NOW"));
        assertThat(omitted).anyMatch(s -> s.startsWith("WAIT_BEFORE_APPLYING"));
        assertThat(omitted).anyMatch(s -> s.startsWith("STUDY_NEXT"));
        assertThat(omitted).anyMatch(s -> s.startsWith("PREPARE_INTERVIEW"));
        assertThat(omitted).anyMatch(s -> s.startsWith("SWITCH_GOAL"));
        assertThat(omitted).anyMatch(s -> s.startsWith("NEGOTIATE_OFFER"));
    }

    @Test
    void careerHealthIsHonestlyNotComputedWithoutAStrategyRow() {
        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) result.get("careerHealth");
        assertThat(health.get("value")).isEqualTo("NOT_COMPUTED");
    }

    @Test
    void careerHealthReusesCareerStrategyEngineProbabilityNeverRecomputesIt() {
        when(strategies.findByUserId(userId)).thenReturn(Optional.of(
                CareerStrategy.builder().id(UUID.randomUUID()).userId(userId)
                        .careerSuccessProbability(new BigDecimal("0.92")).build()));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        Map<String, Object> health = (Map<String, Object>) result.get("careerHealth");
        assertThat(health.get("value")).isEqualTo(new BigDecimal("0.92"));
        assertThat(health.get("source")).isEqualTo("CareerStrategyEngine.careerSuccessProbability");
    }

    @Test
    void applyNowListsOnlyAutoApplyReadyOrMustApplyJobsWithRealEvidence() {
        UUID jobId = UUID.randomUUID();
        JobRecommendation ready = JobRecommendation.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .matchScore(95).category("AUTO_APPLY_READY").build();
        JobRecommendation notReady = JobRecommendation.builder().id(UUID.randomUUID()).userId(userId).jobId(UUID.randomUUID())
                .matchScore(50).category("RECOMMENDED").build();
        when(jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(ready, notReady));
        when(jobs.findById(jobId)).thenReturn(Optional.of(Job.builder().id(jobId).title("Backend Engineer").company("Adyen").build()));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        Map<String, Object> applyNow = decisions.stream().filter(d -> "APPLY_NOW".equals(d.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) applyNow.get("evidence");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobsList = (List<Map<String, Object>>) evidence.get("jobs");
        assertThat(jobsList).hasSize(1);
        assertThat(jobsList.get(0)).containsEntry("company", "Adyen").containsEntry("title", "Backend Engineer");
    }

    @Test
    void studyNextCitesRealSkillGapEvidenceNeverFabricatesAPercentage() {
        when(skillGap.isEnabled()).thenReturn(true);
        when(skillGap.compute(userId)).thenReturn(Map.of(
                "learningPriority", List.of("kubernetes"),
                "criticalSkills", List.of("kubernetes", "terraform"),
                "sampleSize", 20));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        Map<String, Object> studyNext = decisions.stream().filter(d -> "STUDY_NEXT".equals(d.get("type"))).findFirst().orElseThrow();
        assertThat(studyNext.get("recommendation")).isEqualTo("Learn kubernetes next");
        assertThat(studyNext.get("confidence")).isEqualTo("HIGH");
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) studyNext.get("evidence");
        assertThat(evidence).containsEntry("sampleSizeJobsAnalyzed", 20);
    }

    @Test
    void waitBeforeApplyingFiresOnlyWhenAComputedLevelIsBelowThreshold() {
        when(promotionReadiness.isEnabled()).thenReturn(true);
        when(promotionReadiness.compute(userId)).thenReturn(Map.of(
                "readinessByLevel", Map.of("SENIOR", Map.of("readinessScore", 30, "evidence", "1 of 5 dimensions computable"))));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        Map<String, Object> wait = decisions.stream().filter(d -> "WAIT_BEFORE_APPLYING".equals(d.get("type"))).findFirst().orElseThrow();
        assertThat(wait.get("confidence")).isEqualTo("HIGH");
    }

    @Test
    void prepareInterviewOnlyFiresForScheduledInterviewsAndReportsRealHistoricPassRate() {
        Interview scheduled = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(UUID.randomUUID())
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_SCHEDULED).build();
        Interview pastPassed = Interview.builder().id(UUID.randomUUID()).userId(userId).jobId(UUID.randomUUID())
                .interviewType(Interview.TYPE_TECHNICAL).result(Interview.RESULT_PASSED).build();
        when(interviews.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(scheduled, pastPassed));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        Map<String, Object> prep = decisions.stream().filter(d -> "PREPARE_INTERVIEW".equals(d.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) prep.get("evidence");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) evidence.get("interviews");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("historicPassRate", 100.0);
    }

    @Test
    void switchGoalOmittedWhenNoGoalHasBeenSetYet() {
        when(promotionReadiness.isEnabled()).thenReturn(true);
        when(promotionReadiness.compute(userId)).thenReturn(Map.of("readinessByLevel", Map.of()));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<String> omitted = (List<String>) result.get("omittedDecisionTypes");
        assertThat(omitted).anyMatch(s -> s.startsWith("SWITCH_GOAL") && s.contains("no career goal"));
    }

    @Test
    void switchGoalFiresWhenAnAlternateLevelHasMeaningfullyHigherReadiness() throws Exception {
        when(promotionReadiness.isEnabled()).thenReturn(true);
        when(promotionReadiness.compute(userId)).thenReturn(Map.of("readinessByLevel", Map.of(
                "ARCHITECT", Map.of("readinessScore", 30, "evidence", "e"),
                "SENIOR", Map.of("readinessScore", 80, "evidence", "e"))));
        when(strategies.findByUserId(userId)).thenReturn(Optional.of(
                CareerStrategy.builder().id(UUID.randomUUID()).userId(userId)
                        .careerGoalJson("{\"targetLevel\":\"ARCHITECT\"}").build()));

        Map<String, Object> result = engine(true).decide(userId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) result.get("decisions");
        Map<String, Object> switchGoal = decisions.stream().filter(d -> "SWITCH_GOAL".equals(d.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) switchGoal.get("evidence");
        assertThat(evidence).containsEntry("alternateLevel", "SENIOR");
    }
}
