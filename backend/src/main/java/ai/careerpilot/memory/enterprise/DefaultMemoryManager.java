package ai.careerpilot.memory.enterprise;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 11.4 — the default {@link MemoryManager}. Enforces {@link MemoryPolicy#maxEntriesPerType}
 * on every {@link #remember} call: when a user's entries of the target type are already at
 * capacity, the single lowest-importance (oldest as tie-break) entry of that type is evicted
 * first — a bounded ring-buffer-by-importance, not unbounded growth.
 */
public class DefaultMemoryManager implements MemoryManager {

    private final InMemoryMemoryStore store;
    private final MemoryClassifier classifier;
    private final MemoryPolicy policy;
    private final MemoryMetrics metrics;

    public DefaultMemoryManager(InMemoryMemoryStore store, MemoryClassifier classifier, MemoryPolicy policy, MemoryMetrics metrics) {
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.metrics = metrics;
    }

    @Override
    public MemoryEntry remember(UUID userId, String content, MemoryType typeHint) {
        MemoryType type = typeHint != null ? typeHint : classifier.classify(content);
        MemoryImportance importance = classifier.scoreImportance(content, type);
        MemoryEntry entry = MemoryEntry.create(userId, type, content, importance, Set.of());

        enforceCapacity(userId, type);
        store.add(entry);
        metrics.recordRemember(type.name());
        return entry;
    }

    private void enforceCapacity(UUID userId, MemoryType type) {
        List<MemoryEntry> existing = store.allForType(userId, type);
        if (existing.size() < policy.maxEntriesPerType()) {
            return;
        }
        existing.stream()
                .min(Comparator.comparingDouble((MemoryEntry e) -> e.importance().score())
                        .thenComparing(MemoryEntry::createdAt))
                .ifPresent(toEvict -> store.remove(userId, toEvict.id()));
    }

    @Override
    public boolean forget(UUID userId, UUID memoryId) {
        List<MemoryEntry> entries = store.allFor(userId);
        String typeName = entries.stream()
                .filter(e -> e.id().equals(memoryId))
                .findFirst()
                .map(e -> e.type().name())
                .orElse(null);

        boolean removed = store.remove(userId, memoryId);
        if (removed && typeName != null) {
            metrics.recordForget(typeName);
        }
        return removed;
    }

    @Override
    public List<MemoryEntry> allFor(UUID userId, MemoryType type) {
        return type == null ? store.allFor(userId) : store.allForType(userId, type);
    }
}
