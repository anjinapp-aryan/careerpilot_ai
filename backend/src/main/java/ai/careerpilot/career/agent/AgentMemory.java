package ai.careerpilot.career.agent;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.6 — a bounded, per-user history of the agent's own past {@link AgentReflection}s.
 * Deliberately separate from {@code ai.careerpilot.memory.enterprise.MemoryManager} (Phase
 * 11.4) — that layer classifies arbitrary remembered content; this one is scoped specifically to
 * the agent's own run history, the same "narrower, purpose-built" relationship {@code
 * ai.careerpilot.career.monitor.CareerTimeline} (Phase 11.5) has to it.
 */
public interface AgentMemory {

    void remember(AgentReflection reflection);

    List<AgentReflection> recentFor(UUID userId, int limit);
}
