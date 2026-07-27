package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryConsolidatorTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final DefaultMemoryClassifier classifier = new DefaultMemoryClassifier();
    private final InMemoryMemoryMetrics metrics = new InMemoryMemoryMetrics();
    private final MemoryPolicy policy = new MemoryPolicy(Duration.ofHours(1), 2, 200);
    private final DefaultMemoryConsolidator consolidator = new DefaultMemoryConsolidator(store, classifier, policy, metrics);

    private MemoryEntry rawEntry(UUID userId, String content, Instant createdAt, int accessCount) {
        return new MemoryEntry(UUID.randomUUID(), userId, MemoryType.WORKING, content,
                new MemoryImportance(0.5), createdAt, createdAt, accessCount, Set.of());
    }

    @Test
    void frequentlyAccessedFreshEntryIsPromotedRegardlessOfAge() {
        UUID userId = UUID.randomUUID();
        store.add(rawEntry(userId, "my career goal is staff engineer", Instant.now(), 5));

        ConsolidationSummary summary = consolidator.consolidate(userId);

        assertThat(summary.promoted()).isEqualTo(1);
        assertThat(summary.evicted()).isZero();
        assertThat(store.allForType(userId, MemoryType.CAREER)).hasSize(1);
        assertThat(store.allForType(userId, MemoryType.WORKING)).isEmpty();
    }

    @Test
    void frequentlyAccessedButUnclassifiableContentPromotesToLongTerm() {
        UUID userId = UUID.randomUUID();
        store.add(rawEntry(userId, "just a plain unclassified note", Instant.now(), 5));

        consolidator.consolidate(userId);

        assertThat(store.allForType(userId, MemoryType.LONG_TERM)).hasSize(1);
    }

    @Test
    void agedButRarelyAccessedEntryIsEvicted() {
        UUID userId = UUID.randomUUID();
        Instant longAgo = Instant.now().minus(Duration.ofHours(2));
        store.add(rawEntry(userId, "some old note", longAgo, 0));

        ConsolidationSummary summary = consolidator.consolidate(userId);

        assertThat(summary.evicted()).isEqualTo(1);
        assertThat(summary.promoted()).isZero();
        assertThat(store.allFor(userId)).isEmpty();
    }

    @Test
    void freshAndRarelyAccessedEntryIsLeftUntouched() {
        UUID userId = UUID.randomUUID();
        store.add(rawEntry(userId, "brand new note", Instant.now(), 0));

        ConsolidationSummary summary = consolidator.consolidate(userId);

        assertThat(summary.promoted()).isZero();
        assertThat(summary.evicted()).isZero();
        assertThat(store.allForType(userId, MemoryType.WORKING)).hasSize(1);
    }

    @Test
    void onlyWorkingMemoryIsConsidered_otherTypesUntouched() {
        UUID userId = UUID.randomUUID();
        MemoryEntry alreadyCareer = new MemoryEntry(UUID.randomUUID(), userId, MemoryType.CAREER, "existing career memory",
                new MemoryImportance(0.5), Instant.now().minus(Duration.ofDays(10)), Instant.now(), 0, Set.of());
        store.add(alreadyCareer);

        ConsolidationSummary summary = consolidator.consolidate(userId);

        assertThat(summary.promoted()).isZero();
        assertThat(summary.evicted()).isZero();
        assertThat(store.allForType(userId, MemoryType.CAREER)).hasSize(1);
    }
}
