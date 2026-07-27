package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIntentHistoryTest {

    private final InMemoryIntentHistory history = new InMemoryIntentHistory();

    @Test
    void recentForUnknownUserReturnsEmpty() {
        assertThat(history.recentFor(UUID.randomUUID(), 5)).isEmpty();
    }

    @Test
    void recordsMostRecentFirst() {
        UUID userId = UUID.randomUUID();
        history.record(userId, IntentResult.none("first"));
        history.record(userId, IntentResult.none("second"));

        List<IntentResult> recent = history.recentFor(userId, 5);

        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).reason()).isEqualTo("second");
    }

    @Test
    void isBoundedPerUser() {
        UUID userId = UUID.randomUUID();
        for (int i = 0; i < 60; i++) {
            history.record(userId, IntentResult.none("entry-" + i));
        }

        assertThat(history.recentFor(userId, 100)).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void historyIsIsolatedPerUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        history.record(userA, IntentResult.none("a"));

        assertThat(history.recentFor(userB, 5)).isEmpty();
    }
}
