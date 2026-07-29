package ai.careerpilot.missionexecution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryExecutionHistoryTest {

    private final InMemoryExecutionHistory history = new InMemoryExecutionHistory();

    private MissionExecutionPlan plan(UUID missionId) {
        return new MissionExecutionPlan(missionId, List.of(), new ExecutionQueue(List.of(), List.of(), List.of()),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, Instant.now());
    }

    @Test
    void remembersAndReturnsMostRecentFirst() {
        UUID missionId = UUID.randomUUID();
        MissionExecutionPlan first = plan(missionId);
        MissionExecutionPlan second = plan(missionId);

        history.remember(first);
        history.remember(second);

        List<MissionExecutionPlan> recent = history.recentFor(missionId, 5);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0)).isSameAs(second);
    }

    @Test
    void separateMissionsDoNotShareHistory() {
        UUID mission1 = UUID.randomUUID();
        UUID mission2 = UUID.randomUUID();
        history.remember(plan(mission1));

        assertThat(history.recentFor(mission2, 5)).isEmpty();
    }

    @Test
    void unknownMissionReturnsEmptyRatherThanThrowing() {
        assertThat(history.recentFor(UUID.randomUUID(), 5)).isEmpty();
    }
}
