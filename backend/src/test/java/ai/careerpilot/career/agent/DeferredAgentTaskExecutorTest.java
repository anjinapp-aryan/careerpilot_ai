package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeferredAgentTaskExecutorTest {

    private final DeferredAgentTaskExecutor executor = new DeferredAgentTaskExecutor();

    @Test
    void alwaysReturnsDeferredRegardlessOfTaskType() {
        for (AgentTaskType type : AgentTaskType.values()) {
            AgentTaskResult result = executor.execute(type, UUID.randomUUID());
            assertThat(result.outcome()).isEqualTo(TaskOutcome.DEFERRED);
            assertThat(result.type()).isEqualTo(type);
            assertThat(result.detail()).contains("not yet connected");
        }
    }
}
