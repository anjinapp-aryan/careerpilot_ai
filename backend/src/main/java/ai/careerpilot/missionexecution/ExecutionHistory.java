package ai.careerpilot.missionexecution;

import java.util.List;
import java.util.UUID;

/**
 * Pre-Phase-9 Hardening — a bounded, per-mission history of past {@link MissionExecutionPlan}s,
 * the same shape as {@code ai.careerpilot.career.agent.AgentMemory}/{@code
 * ai.careerpilot.career.monitor.CareerTimeline} elsewhere in this codebase. Nothing reads it yet
 * — it exists for a future replanning phase that compares {@link ExpectedOutcome} against {@link
 * ExecutionResultSummary}.
 */
public interface ExecutionHistory {

    void remember(MissionExecutionPlan plan);

    List<MissionExecutionPlan> recentFor(UUID missionId, int limit);
}
