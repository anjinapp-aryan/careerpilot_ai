package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentMemoryTest {

    private final InMemoryAgentMemory memory = new InMemoryAgentMemory();

    @Test
    void recentForUnknownUserReturnsEmpty() {
        assertThat(memory.recentFor(UUID.randomUUID(), 5)).isEmpty();
    }

    @Test
    void recordsMostRecentFirst() {
        UUID userId = UUID.randomUUID();
        memory.remember(AgentReflection.skipped(userId, "first"));
        memory.remember(AgentReflection.skipped(userId, "second"));

        List<AgentReflection> recent = memory.recentFor(userId, 5);

        assertThat(recent.get(0).assessment()).isEqualTo("second");
    }

    @Test
    void nullOrUserlessReflectionIsIgnoredSafely() {
        memory.remember(null);
        assertThat(memory.recentFor(UUID.randomUUID(), 5)).isEmpty();
    }

    @Test
    void isolatedPerUser() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        memory.remember(AgentReflection.skipped(userA, "a"));

        assertThat(memory.recentFor(userB, 5)).isEmpty();
    }
}
