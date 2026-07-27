package ai.careerpilot.career.agent;

import java.util.UUID;

/** Phase 11.6 — the top-level facade: observe → plan → (deferred) execute → reflect → remember. */
public interface AutonomousCareerAgent {

    AgentReflection runOnce(UUID userId);
}
