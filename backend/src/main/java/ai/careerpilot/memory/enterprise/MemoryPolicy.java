package ai.careerpilot.memory.enterprise;

import java.time.Duration;

/**
 * Phase 11.4 — the tunable rules {@link MemoryConsolidator} and {@link MemoryManager} enforce.
 *
 * @param workingMemoryTtl          how long a {@code WORKING} entry may sit unpromoted before
 *                                  {@link MemoryConsolidator} either promotes or evicts it
 * @param promotionAccessThreshold  access count at/above which a {@code WORKING} entry is
 *                                  promoted regardless of age (frequently-referenced memory
 *                                  earns permanence faster)
 * @param maxEntriesPerType         per-user, per-type cap; {@link MemoryManager} evicts the
 *                                  lowest-importance/oldest entry when a new one would exceed it
 */
public record MemoryPolicy(Duration workingMemoryTtl, int promotionAccessThreshold, int maxEntriesPerType) {

    public static MemoryPolicy defaults() {
        return new MemoryPolicy(Duration.ofHours(24), 3, 200);
    }
}
