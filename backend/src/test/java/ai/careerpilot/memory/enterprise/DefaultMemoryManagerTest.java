package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMemoryManagerTest {

    private final InMemoryMemoryStore store = new InMemoryMemoryStore();
    private final DefaultMemoryClassifier classifier = new DefaultMemoryClassifier();
    private final InMemoryMemoryMetrics metrics = new InMemoryMemoryMetrics();

    @Test
    void rememberAutoClassifiesWhenNoTypeHintGiven() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, policy, metrics);
        UUID userId = UUID.randomUUID();

        MemoryEntry entry = manager.remember(userId, "I decided to accept the offer", null);

        assertThat(entry.type()).isEqualTo(MemoryType.DECISION);
        assertThat(metrics.rememberCount("DECISION")).isEqualTo(1);
    }

    @Test
    void rememberRespectsExplicitTypeHint() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, policy, metrics);
        UUID userId = UUID.randomUUID();

        MemoryEntry entry = manager.remember(userId, "arbitrary note", MemoryType.CAREER);

        assertThat(entry.type()).isEqualTo(MemoryType.CAREER);
    }

    @Test
    void forgetRemovesEntryAndRecordsMetric() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, policy, metrics);
        UUID userId = UUID.randomUUID();
        MemoryEntry entry = manager.remember(userId, "note", MemoryType.WORKING);

        boolean removed = manager.forget(userId, entry.id());

        assertThat(removed).isTrue();
        assertThat(manager.allFor(userId, MemoryType.WORKING)).isEmpty();
        assertThat(metrics.forgetCount("WORKING")).isEqualTo(1);
    }

    @Test
    void forgetUnknownIdReturnsFalse() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, policy, metrics);

        assertThat(manager.forget(UUID.randomUUID(), UUID.randomUUID())).isFalse();
    }

    @Test
    void enforcesPerTypeCapacity_evictsLowestImportanceWhenFull() {
        MemoryPolicy tinyPolicy = new MemoryPolicy(Duration.ofHours(24), 3, 2);
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, tinyPolicy, metrics);
        UUID userId = UUID.randomUUID();

        manager.remember(userId, "low importance note", MemoryType.WORKING);
        manager.remember(userId, "this is critical and urgent and quite detailed content here padding padding padding padding", MemoryType.WORKING);
        manager.remember(userId, "another low importance note", MemoryType.WORKING);

        List<MemoryEntry> remaining = manager.allFor(userId, MemoryType.WORKING);
        assertThat(remaining).hasSize(2);
        assertThat(remaining).noneMatch(e -> e.content().equals("low importance note"));
    }

    @Test
    void allForWithNullTypeReturnsEverything() {
        MemoryPolicy policy = MemoryPolicy.defaults();
        DefaultMemoryManager manager = new DefaultMemoryManager(store, classifier, policy, metrics);
        UUID userId = UUID.randomUUID();
        manager.remember(userId, "note1", MemoryType.WORKING);
        manager.remember(userId, "note2", MemoryType.CAREER);

        assertThat(manager.allFor(userId, null)).hasSize(2);
    }
}
