package ai.careerpilot.mission;

import ai.careerpilot.domain.*;
import ai.careerpilot.mission.MissionOrchestratorService.OrchestrationResult;
import ai.careerpilot.repo.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mission Orchestrator, Phase 5 — {@link MissionOrchestratorService}. Pins the deterministic
 * decision rules against the phase spec's own worked example (resume score 70, missing
 * Kubernetes → skill-gap + resume workflows recommended) and confirms the orchestrator only
 * recommends, never triggers execution.
 */
class MissionOrchestratorServiceTest {

    private final CareerMissionRepository missions = mock(CareerMissionRepository.class);
    private final StrategyPlanRepository strategyPlans = mock(StrategyPlanRepository.class);
    private final CareerGoalRepository goals = mock(CareerGoalRepository.class);
    private final WorkflowRunRepository workflowRuns = mock(WorkflowRunRepository.class);
    private final MissionExecutionRepository executions = mock(MissionExecutionRepository.class);
    private final WorkflowDecisionLogRepository decisionLogs = mock(WorkflowDecisionLogRepository.class);
    private final MissionOrchestratorService service = new MissionOrchestratorService(
            missions, strategyPlans, goals, workflowRuns, executions, decisionLogs);

    private final UUID userId = UUID.randomUUID();
    private final UUID missionId = UUID.randomUUID();

    private CareerMission mission() {
        return CareerMission.builder().id(missionId).userId(userId).missionStatement("stmt").targetRole("role").build();
    }

    private void stubPersistence() {
        when(executions.save(any(MissionExecution.class))).thenAnswer(inv -> {
            MissionExecution e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });
        when(decisionLogs.save(any(WorkflowDecisionLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void noStrategyPlanYetRecommendsOnlyCareerStrategy() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.empty());
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        stubPersistence();

        OrchestrationResult result = service.run(userId, missionId);

        assertThat(result.decisions()).hasSize(1);
        assertThat(result.decisions().get(0).workflowId()).isEqualTo("CAREER_STRATEGY_V1");
    }

    @Test
    void matchesThePhaseSpecsWorkedExample_skillGapAndResumeWorkflowsRecommended() {
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).timeframeDays(90).build();
        CareerGoal skillAction = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId).strategyPlanId(plan.getId())
                .title("Improve Kubernetes knowledge").status(MissionStatus.ACTIVE).build();
        WorkflowRun run = WorkflowRun.builder().id(UUID.randomUUID()).userId(userId).resumeScore(70).build();

        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(skillAction));
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        stubPersistence();

        OrchestrationResult result = service.run(userId, missionId);

        assertThat(result.execution().getResumeScoreAtRun()).isEqualTo(70);
        assertThat(result.decisions()).extracting("workflowId")
                .containsExactlyInAnyOrder("SKILL_ANALYSIS_V1", "RESUME_OPTIMIZATION_V1");
    }

    @Test
    void resumeScoreAtOrAboveThresholdSkipsResumeOptimization() {
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).build();
        WorkflowRun run = WorkflowRun.builder().id(UUID.randomUUID()).userId(userId).resumeScore(90).build();

        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of());
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(run));
        stubPersistence();

        OrchestrationResult result = service.run(userId, missionId);

        assertThat(result.decisions()).extracting("workflowId").doesNotContain("RESUME_OPTIMIZATION_V1");
    }

    @Test
    void completedApplyActionRecommendsInterviewPrep() {
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId).build();
        CareerGoal applyAction = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId).strategyPlanId(plan.getId())
                .title("Apply for Architect positions").status(MissionStatus.COMPLETED).build();

        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId)).thenReturn(Optional.of(plan));
        when(goals.findByStrategyPlanIdOrderByCreatedAtAsc(plan.getId())).thenReturn(List.of(applyAction));
        when(workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        stubPersistence();

        OrchestrationResult result = service.run(userId, missionId);

        assertThat(result.decisions()).extracting("workflowId").contains("INTERVIEW_PREPARATION_V1");
        assertThat(result.decisions()).extracting("workflowId").doesNotContain("JOB_DISCOVERY_V1");
    }

    @Test
    void runThrowsMissionNotFoundForWrongOwner() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.run(userId, missionId)).isInstanceOf(MissionNotFoundException.class);
    }

    @Test
    void statusReturnsEmptyWhenOrchestratorNeverRan() {
        when(missions.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(mission()));
        when(executions.findFirstByMissionIdOrderByRanAtDesc(missionId)).thenReturn(Optional.empty());

        assertThat(service.status(userId, missionId)).isEmpty();
    }
}
