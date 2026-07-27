package ai.careerpilot.memory.enterprise;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.4 — the default {@link MemorySearch}: case-insensitive substring match against each
 * entry's content, ranked by importance. A blank query returns no results rather than the whole
 * store (matches every other resolver's "blank input, no match" convention in this codebase).
 */
public class DefaultMemorySearch implements MemorySearch {

    private final InMemoryMemoryStore store;
    private final MemoryMetrics metrics;

    public DefaultMemorySearch(InMemoryMemoryStore store, MemoryMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    @Override
    public List<MemoryEntry> search(UUID userId, String query, int limit) {
        long start = System.currentTimeMillis();
        List<MemoryEntry> results;
        if (query == null || query.isBlank()) {
            results = List.of();
        } else {
            String lower = query.toLowerCase();
            results = store.allFor(userId).stream()
                    .filter(e -> e.content() != null && e.content().toLowerCase().contains(lower))
                    .sorted(Comparator.comparingDouble((MemoryEntry e) -> e.importance().score()).reversed())
                    .limit(Math.max(0, limit))
                    .toList();
        }
        metrics.recordSearch(System.currentTimeMillis() - start, results.size());
        return results;
    }
}
