package ai.careerpilot.memory.enterprise;

/** Phase 11.4 — {@link MemoryConsolidator#consolidate} output: how many entries changed state. */
public record ConsolidationSummary(int promoted, int evicted) {
}
