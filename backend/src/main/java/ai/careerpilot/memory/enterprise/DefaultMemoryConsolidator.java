package ai.careerpilot.memory.enterprise;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 11.4 — the default {@link MemoryConsolidator}. For every {@code WORKING} entry:
 * <ul>
 *   <li>frequently accessed (accessCount ≥ {@link MemoryPolicy#promotionAccessThreshold()}) →
 *       promoted, regardless of age — re-classified via {@link MemoryClassifier} in case its
 *       content now clearly belongs to a specific type (e.g. {@code CAREER}/{@code SKILL}); if
 *       the classifier still says {@code WORKING}, it falls through to the generic {@code
 *       LONG_TERM} type instead.</li>
 *   <li>aged past {@link MemoryPolicy#workingMemoryTtl()} without enough access → evicted
 *       entirely; it never proved useful.</li>
 *   <li>neither aged nor frequently accessed → left untouched in {@code WORKING}.</li>
 * </ul>
 */
public class DefaultMemoryConsolidator implements MemoryConsolidator {

    private final InMemoryMemoryStore store;
    private final MemoryClassifier classifier;
    private final MemoryPolicy policy;
    private final MemoryMetrics metrics;

    public DefaultMemoryConsolidator(InMemoryMemoryStore store, MemoryClassifier classifier, MemoryPolicy policy, MemoryMetrics metrics) {
        this.store = store;
        this.classifier = classifier;
        this.policy = policy;
        this.metrics = metrics;
    }

    @Override
    public ConsolidationSummary consolidate(UUID userId) {
        List<MemoryEntry> working = store.allForType(userId, MemoryType.WORKING);
        Instant now = Instant.now();
        int promoted = 0;
        int evicted = 0;

        for (MemoryEntry entry : working) {
            boolean frequentlyAccessed = entry.accessCount() >= policy.promotionAccessThreshold();
            boolean aged = now.isAfter(entry.createdAt().plus(policy.workingMemoryTtl()));

            if (frequentlyAccessed) {
                MemoryType reclassified = classifier.classify(entry.content());
                MemoryType promotedType = reclassified == MemoryType.WORKING ? MemoryType.LONG_TERM : reclassified;
                store.replace(entry.reclassified(promotedType));
                promoted++;
            } else if (aged) {
                store.remove(userId, entry.id());
                evicted++;
            }
        }

        metrics.recordConsolidation(promoted, evicted);
        return new ConsolidationSummary(promoted, evicted);
    }
}
