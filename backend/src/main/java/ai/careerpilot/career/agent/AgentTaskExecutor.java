package ai.careerpilot.career.agent;

import java.util.UUID;

/** Phase 11.6 — dispatches one planned {@link AgentTaskType} for a user. */
public interface AgentTaskExecutor {

    AgentTaskResult execute(AgentTaskType type, UUID userId);
}
