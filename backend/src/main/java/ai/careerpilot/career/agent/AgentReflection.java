package ai.careerpilot.career.agent;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.6 — the agent's post-run self-assessment: what it observed, what it planned, what
 * happened when it tried to act, and a plain-text summary. {@link AutonomousCareerAgent}
 * produces exactly one of these per {@link AutonomousCareerAgent#runOnce} call, whether or not
 * anything was actually planned.
 */
public record AgentReflection(UUID userId, AgentExecutionPlan plan, List<AgentTaskResult> results,
                               String assessment, Instant reflectedAt) {

    public static AgentReflection skipped(UUID userId, String reason) {
        return new AgentReflection(userId, AgentExecutionPlan.empty(userId, reason), List.of(), reason, Instant.now());
    }
}
