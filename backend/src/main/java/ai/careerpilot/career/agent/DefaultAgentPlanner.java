package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerAlert;
import ai.careerpilot.career.monitor.CareerAlertType;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 11.6 — the default {@link AgentPlanner}. Rule-based, one-to-one mapping from each {@code
 * CareerAlertType} (Phase 11.5) to the {@link AgentTaskType} that would address it — the same
 * kind of static, explicit mapping {@code DefaultCapabilityPlanner} (Phase 11.2) uses for
 * {@code IntentType → CapabilityType}. {@link AgentTaskType#APPLICATION_TRACKING} has no
 * corresponding {@code CareerAlertType} signal and is never triggered by this planner — a
 * future, richer planner could add it.
 *
 * <p><b>Phase 7A</b> — when the observation carries a {@link MissionContext} (attached only when
 * {@code career.mission.agent.enabled} is on and the user has an active Mission), its {@code
 * recommendedWorkflowIds} — {@code MissionOrchestratorService}'s (Phase 5) own "what should run
 * next" decision list, reached via {@code MissionAwareDailyBriefService} (Phase 6A) — are
 * translated into {@link AgentTaskType}s first, via {@link #WORKFLOW_TO_TASK}: the exact same
 * kind of static id→task lookup as {@link #ALERT_TO_TASK} below, not a new decision. The actual
 * "what should run next" business rule is still made entirely by {@code
 * MissionOrchestratorService}; this planner only translates its output into this agent's own task
 * vocabulary, then tops up remaining capacity with {@code CareerMonitor} alerts exactly as before.
 */
public class DefaultAgentPlanner implements AgentPlanner {

    private static final Map<CareerAlertType, AgentTaskType> ALERT_TO_TASK = Map.of(
            CareerAlertType.JOB_MATCH, AgentTaskType.JOB_DISCOVERY,
            CareerAlertType.RESUME_OUTDATED, AgentTaskType.RESUME_OPTIMIZATION,
            CareerAlertType.MISSING_CERTIFICATION, AgentTaskType.SKILL_GAP_DETECTION,
            CareerAlertType.SALARY_BELOW_MARKET, AgentTaskType.SALARY_TREND_MONITORING,
            CareerAlertType.PROMOTION_READY, AgentTaskType.CAREER_ROADMAP_UPDATE,
            CareerAlertType.INTERVIEW_REMINDER, AgentTaskType.INTERVIEW_PLANNING,
            CareerAlertType.LEARNING_SUGGESTION, AgentTaskType.LEARNING_ROADMAP);

    /** Phase 7A — Workflow Registry (Phase 4) ids → this agent's own task taxonomy. */
    private static final Map<String, AgentTaskType> WORKFLOW_TO_TASK = Map.of(
            "JOB_DISCOVERY_V1", AgentTaskType.JOB_DISCOVERY,
            "RESUME_OPTIMIZATION_V1", AgentTaskType.RESUME_OPTIMIZATION,
            "INTERVIEW_PREPARATION_V1", AgentTaskType.INTERVIEW_PLANNING,
            "SKILL_ANALYSIS_V1", AgentTaskType.SKILL_GAP_DETECTION,
            "CAREER_STRATEGY_V1", AgentTaskType.CAREER_ROADMAP_UPDATE);

    private final int maxTasksPerPlan;

    public DefaultAgentPlanner(int maxTasksPerPlan) {
        this.maxTasksPerPlan = maxTasksPerPlan;
    }

    @Override
    public AgentExecutionPlan plan(AgentObservation observation) {
        boolean hasMission = observation != null && observation.missionContext() != null
                && !observation.missionContext().recommendedWorkflowIds().isEmpty();
        if (observation == null || (!hasMission && observation.signals().isEmpty())) {
            return AgentExecutionPlan.empty(observation == null ? null : observation.userId(), "no signals observed");
        }

        Set<AgentTaskType> tasks = new LinkedHashSet<>();

        if (hasMission) {
            for (String workflowId : observation.missionContext().recommendedWorkflowIds()) {
                AgentTaskType task = WORKFLOW_TO_TASK.get(workflowId);
                if (task != null) {
                    tasks.add(task);
                }
                if (tasks.size() >= maxTasksPerPlan) {
                    break;
                }
            }
        }

        if (tasks.size() < maxTasksPerPlan) {
            for (CareerAlert signal : observation.signals()) {
                AgentTaskType task = ALERT_TO_TASK.get(signal.type());
                if (task != null) {
                    tasks.add(task);
                }
                if (tasks.size() >= maxTasksPerPlan) {
                    break;
                }
            }
        }

        if (tasks.isEmpty()) {
            return AgentExecutionPlan.empty(observation.userId(), "no signal mapped to a known task");
        }

        String reason = hasMission
                ? "planned from mission " + observation.missionContext().missionId()
                        + "'s recommendations plus " + observation.signals().size() + " observed signal(s)"
                : "planned from " + observation.signals().size() + " observed signal(s)";

        return new AgentExecutionPlan(observation.userId(), List.copyOf(tasks), reason, Instant.now());
    }
}
