package ai.careerpilot.memory.enterprise;

import java.util.UUID;

/**
 * Phase 11.4 — the working-memory → long-term-memory lifecycle: promotes qualifying {@code
 * WORKING} entries into a more durable type, evicts stale ones that never earned promotion.
 * Nothing calls this automatically in this phase (no scheduler wired) — a future caller invokes
 * {@link #consolidate} on whatever cadence it chooses.
 */
public interface MemoryConsolidator {

    ConsolidationSummary consolidate(UUID userId);
}
