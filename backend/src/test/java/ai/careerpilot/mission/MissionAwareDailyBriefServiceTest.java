package ai.careerpilot.mission;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.domain.MissionExecution;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.jobdiscovery.international.CountryMatchingCapability.CountryFitResult;
import ai.careerpilot.mission.MissionAwareDailyBriefService.MissionBrief;
import ai.careerpilot.mission.MissionOrchestratorService.Decision;
import ai.careerpilot.mission.MissionOrchestratorService.OrchestrationResult;
import ai.careerpilot.repo.CareerGoalRepository;
import ai.careerpilot.repo.CareerMissionRepository;
import ai.careerpilot.repo.StrategyPlanRepository;
import ai.careerpilot.strategy.StrategyEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 6A — {@link MissionAwareDailyBriefService}. Pins the flag-off/no-mission empty cases and
 * that a real mission with an in-progress strategy plan produces the exact fields the phase
 * spec's own worked example describes (progress %, current/alternative strategy country, today's
 * tasks, risks, recommendation).
 *
 * <p>Phase 6A.1 — country ranking is asserted via {@link StrategyEvaluationService#rankCountriesForMission}
 * (the new dependency, replacing the direct {@code CountryMatchingCapability} dependency), and a
 * dedicated test pins the {@link WorkflowPlanner} extension point: absent (the default,
 * {@code ObjectProvider#getIfAvailable} returning {@code null}) falls back to the pre-6A.1 manual
 * task assembly; present, it is consulted instead.
 */
class MissionAwareDailyBriefServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final StrategyPlanRepository strategyPlans = mock(StrategyPlanRepository.class);
    private final CareerGoalRepository goals = mock(CareerGoalRepository.class);
    private final MissionOrchestratorService orchestrator = mock(MissionOrchestratorService.class);
    private final StrategyEvaluationService strategyEvaluation = mock(StrategyEvaluationService.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID missionId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private MissionAwareDailyBriefService service(boolean enabled) {
        return service(enabled, providerFor(null));
    }

    private MissionAwareDailyBriefService service(boolean enabled, ObjectProvider<WorkflowPlanner> plannerProvider) {
        return new MissionAwareDailyBriefService(missions, strategyPlans, goals, orchestrator,
                strategyEvaluation, plannerProvider, enabled);
    }

    private CareerMission mission() {
        return CareerMission.builder().id(missionId).userId(userId)
                .missionStatement("Become Principal Engineer Abroad").targetRole("Principal Engineer")
                .status(MissionStatus.ACTIVE).build();
    }

    @Test
    void disabledFlagReturnsEmptyWithoutTouchingAnyRepository() {
        MissionAwareDailyBriefService s = service(false);

        assertThat(s.buildFor(userId)).isEmpty();
        verifyNoInteractions(missions, strategyPlans, goals, orchestrator, strategyEvaluation);
    }

    @Test
    void noActiveMissionReturnsEmpty() {
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThat(service(true).buildFor(userId)).isEmpty();
    }

    @Test
    void matchesThePhaseSpecsWorkedExampleShape() {
        CareerMission m = mission();
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId)
                .timeframeDays(90).generatedAt(Instant.now().minusSeconds(60L * 60 * 24 * 20)).build(); // 20 days elapsed
        CareerGoal kafkaAction = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .strategyPlanId(plan.getId()).title("Improve Kafka knowledge").status(MissionStatus.ACTIVE).build();
        CareerGoal applyAction = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .strategyPlanId(plan.getId()).title("Apply for Principal Engineer positions").status(MissionStatus.COMPLETED).build();

        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(kafkaAction, applyAction));
        when(orchestrator.run(userId, missionId)).thenReturn(new OrchestrationResult(
                MissionExecution.builder().id(UUID.randomUUID()).missionId(missionId).userId(userId).build(),
                List.of(new Decision("JOB_DISCOVERY_V1", "reason"))));
        when(strategyEvaluation.rankCountriesForMission(m)).thenReturn(List.of(
                new CountryFitResult("nl", "Netherlands", 90, "e1"),
                new CountryFitResult("de", "Germany", 80, "e2")));

        MissionBrief brief = service(true).buildFor(userId).orElseThrow();

        assertThat(brief.missionName()).isEqualTo("Become Principal Engineer Abroad");
        assertThat(brief.progressPercent()).isEqualTo(50); // 1 of 2 actions completed
        assertThat(brief.currentStrategyCountry()).isEqualTo("Netherlands");
        assertThat(brief.alternativeStrategyCountry()).isEqualTo("Germany");
        assertThat(brief.todaysMissionTasks()).containsExactly("Improve Kafka knowledge");
        assertThat(brief.priorityWorkflows()).containsExactly("JOB_DISCOVERY_V1");
        assertThat(brief.highRiskAreas()).containsExactly("Kafka gap");
        assertThat(brief.recommendedLearning()).containsExactly("Improve Kafka knowledge");
        assertThat(brief.estimatedCompletionTimeline()).contains("70 days remaining");
        assertThat(brief.recommendation()).contains("Delay interview applications").contains("Kafka");
    }

    @Test
    void orchestratorFailureDegradesGracefullyRatherThanThrowing() {
        CareerMission m = mission();
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.empty());
        when(orchestrator.run(any(), any())).thenThrow(new RuntimeException("boom"));
        when(strategyEvaluation.rankCountriesForMission(m)).thenReturn(List.of());

        MissionBrief brief = service(true).buildFor(userId).orElseThrow();

        assertThat(brief.priorityWorkflows()).isEmpty();
        assertThat(brief.recommendation()).isEqualTo("Stay on track with today's mission tasks.");
    }

    @Test
    void countryRankingFailureDegradesGracefullyRatherThanThrowing() {
        CareerMission m = mission();
        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.empty());
        when(orchestrator.run(userId, missionId)).thenReturn(new OrchestrationResult(
                MissionExecution.builder().id(UUID.randomUUID()).build(), List.of()));
        when(strategyEvaluation.rankCountriesForMission(m)).thenThrow(new RuntimeException("boom"));

        MissionBrief brief = service(true).buildFor(userId).orElseThrow();

        assertThat(brief.currentStrategyCountry()).isNull();
        assertThat(brief.alternativeStrategyCountry()).isNull();
    }

    @Test
    void fallsBackToManualTaskAssemblyWhenNoWorkflowPlannerIsRegistered() {
        CareerMission m = mission();
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).build();
        CareerGoal action = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .strategyPlanId(plan.getId()).title("Improve Kafka knowledge").status(MissionStatus.ACTIVE).build();

        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(action));
        when(orchestrator.run(userId, missionId)).thenReturn(new OrchestrationResult(
                MissionExecution.builder().id(UUID.randomUUID()).build(), List.of()));
        when(strategyEvaluation.rankCountriesForMission(m)).thenReturn(List.of());

        // providerFor(null) is the default used by service(boolean) — no WorkflowPlanner bean exists.
        MissionBrief brief = service(true).buildFor(userId).orElseThrow();

        assertThat(brief.todaysMissionTasks()).containsExactly("Improve Kafka knowledge");
    }

    @Test
    void consultsWorkflowPlannerWhenOneIsRegistered() {
        CareerMission m = mission();
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).build();
        CareerGoal action = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .strategyPlanId(plan.getId()).title("Improve Kafka knowledge").status(MissionStatus.ACTIVE).build();

        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(action));
        when(orchestrator.run(userId, missionId)).thenReturn(new OrchestrationResult(
                MissionExecution.builder().id(UUID.randomUUID()).build(), List.of()));
        when(strategyEvaluation.rankCountriesForMission(m)).thenReturn(List.of());

        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        when(planner.planTodaysTasks(m, List.of(action))).thenReturn(List.of("Planner-selected task"));

        MissionBrief brief = service(true, providerFor(planner)).buildFor(userId).orElseThrow();

        assertThat(brief.todaysMissionTasks()).containsExactly("Planner-selected task");
    }

    @Test
    void workflowPlannerFailureDegradesGracefullyToManualAssembly() {
        CareerMission m = mission();
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).build();
        CareerGoal action = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .strategyPlanId(plan.getId()).title("Improve Kafka knowledge").status(MissionStatus.ACTIVE).build();

        when(missions.findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId, MissionStatus.ACTIVE)).thenReturn(Optional.of(m));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(action));
        when(orchestrator.run(userId, missionId)).thenReturn(new OrchestrationResult(
                MissionExecution.builder().id(UUID.randomUUID()).build(), List.of()));
        when(strategyEvaluation.rankCountriesForMission(m)).thenReturn(List.of());

        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        when(planner.planTodaysTasks(any(), any())).thenThrow(new RuntimeException("boom"));

        MissionBrief brief = service(true, providerFor(planner)).buildFor(userId).orElseThrow();

        assertThat(brief.todaysMissionTasks()).containsExactly("Improve Kafka knowledge");
    }
}
