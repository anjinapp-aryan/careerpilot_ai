package ai.careerpilot.mission;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Mission Orchestrator, Phase 5 — the decision engine named in the phase spec. Reads a Mission's
 * current state (its latest {@link StrategyPlan}'s actions, and the most recent resume score from
 * {@code WorkflowRun}) and recommends which {@code workflow_definition} entries to run next, via
 * simple deterministic rules — no LLM, same discipline as {@code StrategyEvaluationService} and
 * {@code CountryMatchingCapability}.
 *
 * <p><b>Recommends, never executes.</b> Actually running a workflow remains the Workflow
 * Registry's (Phase 4) job, which itself only produces a {@code DEFERRED} record today — the
 * orchestrator's output is a ranked, explained decision list a human (or, later, an approved
 * automation) acts on, matching the "human approval required" principle running through every
 * autonomous-acting part of this codebase.
 */
@Service
public class MissionOrchestratorService {

    private static final int RESUME_SCORE_THRESHOLD = 75;

    private final CareerMissionRepository missions;
    private final StrategyPlanRepository strategyPlans;
    private final CareerGoalRepository goals;
    private final WorkflowRunRepository workflowRuns;
    private final MissionExecutionRepository executions;
    private final WorkflowDecisionLogRepository decisionLogs;

    public MissionOrchestratorService(CareerMissionRepository missions, StrategyPlanRepository strategyPlans,
                                       CareerGoalRepository goals, WorkflowRunRepository workflowRuns,
                                       MissionExecutionRepository executions, WorkflowDecisionLogRepository decisionLogs) {
        this.missions = missions;
        this.strategyPlans = strategyPlans;
        this.goals = goals;
        this.workflowRuns = workflowRuns;
        this.executions = executions;
        this.decisionLogs = decisionLogs;
    }

    public record Decision(String workflowId, String reason) {}
    public record OrchestrationResult(MissionExecution execution, List<Decision> decisions) {}

    @Transactional
    public OrchestrationResult run(UUID userId, UUID missionId) {
        CareerMission mission = missions.findByIdAndUserId(missionId, userId)
                .orElseThrow(() -> new MissionNotFoundException(missionId));

        Optional<StrategyPlan> latestPlan = strategyPlans.findFirstByMissionIdOrderByGeneratedAtDesc(missionId);
        List<CareerGoal> actions = latestPlan
                .map(p -> goals.findByStrategyPlanIdOrderByCreatedAtAsc(p.getId()))
                .orElse(List.of());
        Integer resumeScore = latestResumeScore(userId);

        List<Decision> decisions = decide(latestPlan, actions, resumeScore);

        MissionExecution execution = executions.save(MissionExecution.builder()
                .missionId(mission.getId())
                .userId(userId)
                .decisionSummary(summarize(decisions))
                .resumeScoreAtRun(resumeScore)
                .ranAt(Instant.now())
                .build());

        for (Decision d : decisions) {
            decisionLogs.save(WorkflowDecisionLog.builder()
                    .missionExecutionId(execution.getId())
                    .workflowId(d.workflowId())
                    .reason(d.reason())
                    .build());
        }
        return new OrchestrationResult(execution, decisions);
    }

    /** Latest persisted execution + its decision log, or empty if the orchestrator has never run for this mission. */
    public Optional<OrchestrationResult> status(UUID userId, UUID missionId) {
        missions.findByIdAndUserId(missionId, userId).orElseThrow(() -> new MissionNotFoundException(missionId));
        return executions.findFirstByMissionIdOrderByRanAtDesc(missionId).map(execution -> {
            List<Decision> decisions = decisionLogs.findByMissionExecutionIdOrderByCreatedAtAsc(execution.getId())
                    .stream().map(l -> new Decision(l.getWorkflowId(), l.getReason())).toList();
            return new OrchestrationResult(execution, decisions);
        });
    }

    private List<Decision> decide(Optional<StrategyPlan> latestPlan, List<CareerGoal> actions, Integer resumeScore) {
        List<Decision> out = new ArrayList<>();

        if (latestPlan.isEmpty()) {
            out.add(new Decision("CAREER_STRATEGY_V1", "No strategy plan exists yet for this mission."));
            return out; // nothing else is actionable before a plan exists
        }

        boolean hasIncompleteSkillAction = actions.stream()
                .anyMatch(a -> a.getStatus() == MissionStatus.ACTIVE && a.getTitle().startsWith("Improve "));
        if (hasIncompleteSkillAction) {
            out.add(new Decision("SKILL_ANALYSIS_V1", "One or more skill-building actions are still incomplete."));
        }

        if (resumeScore != null && resumeScore < RESUME_SCORE_THRESHOLD) {
            out.add(new Decision("RESUME_OPTIMIZATION_V1",
                    "Resume score " + resumeScore + " is below the " + RESUME_SCORE_THRESHOLD + " threshold."));
        }

        boolean hasIncompleteApply = actions.stream()
                .anyMatch(a -> a.getStatus() == MissionStatus.ACTIVE && a.getTitle().startsWith("Apply for"));
        if (hasIncompleteApply) {
            out.add(new Decision("JOB_DISCOVERY_V1", "The apply-for-target-role action has not started yet."));
        }

        boolean hasCompletedApply = actions.stream()
                .anyMatch(a -> a.getStatus() == MissionStatus.COMPLETED && a.getTitle().startsWith("Apply for"));
        if (hasCompletedApply) {
            out.add(new Decision("INTERVIEW_PREPARATION_V1", "Applications are underway — time to prepare for interviews."));
        }

        return out;
    }

    private Integer latestResumeScore(UUID userId) {
        return workflowRuns.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(WorkflowRun::getResumeScore)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static String summarize(List<Decision> decisions) {
        if (decisions.isEmpty()) return "No workflow recommendations — mission state looks nominal.";
        return "Recommended " + decisions.size() + " workflow(s): "
                + String.join(", ", decisions.stream().map(Decision::workflowId).toList());
    }
}
