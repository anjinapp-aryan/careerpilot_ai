package ai.careerpilot.intent;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.1 — a bounded, per-user record of recent {@link IntentResult}s. Exists for a future
 * caller (e.g. Phase 11.2's Capability Planner) to consider recent intent history when planning
 * — nothing reads it yet in this phase.
 */
public interface IntentHistory {

    void record(UUID userId, IntentResult result);

    List<IntentResult> recentFor(UUID userId, int limit);
}
