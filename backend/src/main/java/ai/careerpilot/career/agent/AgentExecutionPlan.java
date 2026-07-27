package ai.careerpilot.career.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.6 — {@link AgentPlanner}'s decision: which {@link AgentTaskType}s are warranted given
 * an {@link AgentObservation}, and why. Planning output only — {@link
 * AutonomousCareerAgent} is what actually dispatches each task (via {@link AgentTaskExecutor}).
 */
public record AgentExecutionPlan(UUID userId, List<AgentTaskType> tasks, String reason, Instant plannedAt) {

    public static AgentExecutionPlan empty(UUID userId, String reason) {
        return new AgentExecutionPlan(userId, List.of(), reason, Instant.now());
    }

    public boolean isEmpty() {
        return tasks.isEmpty();
    }
}
