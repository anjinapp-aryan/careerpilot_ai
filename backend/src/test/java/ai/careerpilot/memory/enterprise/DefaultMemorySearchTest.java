package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemorySearchTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final InMemoryMemoryMetrics metrics = new InMemoryMemoryMetrics();
    private final DefaultMemorySearch search = new DefaultMemorySearch(store, metrics);

    @Test
    void findsMatchingContentCaseInsensitively() {
        UUID userId = UUID.randomUUID();
        store.add(MemoryEntry.create(userId, MemoryType.CAREER, "Targeting a Staff Engineer role", new MemoryImportance(0.5), Set.of()));
        store.add(MemoryEntry.create(userId, MemoryType.SKILL, "Proficient in Kubernetes", new MemoryImportance(0.5), Set.of()));

        List<MemoryEntry> results = search.search(userId, "staff engineer", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).contains("Staff Engineer");
    }

    @Test
    void blankQueryReturnsNoResults() {
        UUID userId = UUID.randomUUID();
        store.add(MemoryEntry.create(userId, MemoryType.CAREER, "note", new MemoryImportance(0.5), Set.of()));

        assertThat(search.search(userId, "", 10)).isEmpty();
        assertThat(search.search(userId, null, 10)).isEmpty();
    }

    @Test
    void searchIsScopedToRequestingUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        store.add(MemoryEntry.create(userA, MemoryType.CAREER, "staff engineer goal", new MemoryImportance(0.5), Set.of()));

        assertThat(search.search(userB, "staff", 10)).isEmpty();
    }
}
