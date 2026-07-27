package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerAlert;
import ai.careerpilot.career.monitor.CareerAlertType;

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

    private final int maxTasksPerPlan;

    public DefaultAgentPlanner(int maxTasksPerPlan) {
        this.maxTasksPerPlan = maxTasksPerPlan;
    }

    @Override
    public AgentExecutionPlan plan(AgentObservation observation) {
        if (observation == null || observation.signals().isEmpty()) {
            return AgentExecutionPlan.empty(observation == null ? null : observation.userId(), "no signals observed");
        }

        Set<AgentTaskType> tasks = new LinkedHashSet<>();
        for (CareerAlert signal : observation.signals()) {
            AgentTaskType task = ALERT_TO_TASK.get(signal.type());
            if (task != null) {
                tasks.add(task);
            }
            if (tasks.size() >= maxTasksPerPlan) {
                break;
            }
        }

        if (tasks.isEmpty()) {
            return AgentExecutionPlan.empty(observation.userId(), "no signal mapped to a known task");
        }

        return new AgentExecutionPlan(observation.userId(), List.copyOf(tasks),
                "planned from " + observation.signals().size() + " observed signal(s)", java.time.Instant.now());
    }
}
