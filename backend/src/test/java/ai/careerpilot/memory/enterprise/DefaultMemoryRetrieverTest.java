package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryRetrieverTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final InMemoryMemoryMetrics metrics = new InMemoryMemoryMetrics();
    private final DefaultMemoryRetriever retriever = new DefaultMemoryRetriever(store, metrics);

    @Test
    void ranksByImportanceDescending() {
        UUID userId = UUID.randomUUID();
        MemoryEntry low = MemoryEntry.create(userId, MemoryType.WORKING, "low", new MemoryImportance(0.2), Set.of());
        MemoryEntry high = MemoryEntry.create(userId, MemoryType.WORKING, "high", new MemoryImportance(0.9), Set.of());
        store.add(low);
        store.add(high);

        List<MemoryEntry> results = retriever.retrieve(userId, MemoryType.WORKING, 10);

        assertThat(results.get(0).content()).isEqualTo("high");
        assertThat(results.get(1).content()).isEqualTo("low");
    }

    @Test
    void retrievingMarksEntryAsAccessed() {
        UUID userId = UUID.randomUUID();
        MemoryEntry entry = MemoryEntry.create(userId, MemoryType.WORKING, "note", new MemoryImportance(0.5), Set.of());
        store.add(entry);
        assertThat(entry.accessCount()).isZero();

        List<MemoryEntry> results = retriever.retrieve(userId, MemoryType.WORKING, 10);

        assertThat(results.get(0).accessCount()).isEqualTo(1);
        assertThat(store.allFor(userId).get(0).accessCount()).isEqualTo(1);
    }

    @Test
    void respectsLimit() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            store.add(MemoryEntry.create(userId, MemoryType.WORKING, "note-" + i, new MemoryImportance(0.5), Set.of()));
        }

        assertThat(retriever.retrieve(userId, MemoryType.WORKING, 2)).hasSize(2);
    }

    @Test
    void nullTypeRetrievesAcrossAllTypes() {
        UUID userId = UUID.randomUUID();
        store.add(MemoryEntry.create(userId, MemoryType.WORKING, "a", new MemoryImportance(0.5), Set.of()));
        store.add(MemoryEntry.create(userId, MemoryType.CAREER, "b", new MemoryImportance(0.5), Set.of()));

        assertThat(retriever.retrieve(userId, null, 10)).hasSize(2);
    }
}
