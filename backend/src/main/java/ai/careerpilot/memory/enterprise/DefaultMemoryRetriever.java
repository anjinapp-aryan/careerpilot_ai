package ai.careerpilot.memory.enterprise;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.4 — the default {@link MemoryRetriever}. Ranks by importance descending, then
 * recency (most recently accessed first) as tie-break. Every returned entry is marked accessed
 * in the store (bumping {@code accessCount}/{@code lastAccessedAt}) — this is what feeds {@link
 * MemoryConsolidator}'s access-count-based promotion rule; frequently retrieved memory earns
 * promotion out of {@code WORKING} faster.
 */
public class DefaultMemoryRetriever implements MemoryRetriever {

    private final InMemoryMemoryStore store;
    private final MemoryMetrics metrics;

    public DefaultMemoryRetriever(InMemoryMemoryStore store, MemoryMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public List<MemoryEntry> retrieve(UUID userId, MemoryType type, int limit) {
        long start = System.currentTimeMillis();
        List<MemoryEntry> candidates = type == null ? store.allFor(userId) : store.allForType(userId, type);

        List<MemoryEntry> ranked = candidates.stream()
                .sorted(Comparator.comparingDouble((MemoryEntry e) -> e.importance().score()).reversed()
                        .thenComparing(MemoryEntry::lastAccessedAt, Comparator.reverseOrder()))
                .limit(Math.max(0, limit))
                .toList();

        Instant now = Instant.now();
        List<MemoryEntry> accessed = ranked.stream().map(e -> {
            MemoryEntry updated = e.accessed(now);
            store.replace(updated);
            return updated;
        }).toList();

        metrics.recordRetrieval(type == null ? "ALL" : type.name(), System.currentTimeMillis() - start);
        return accessed;
    }
}
