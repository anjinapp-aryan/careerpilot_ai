package ai.careerpilot.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowStateTest {

    @Test
    void nullMapsDefaultToEmptyRatherThanThrowing() {
        WorkflowState state = new WorkflowState(UUID.randomUUID(), UUID.randomUUID(), "wf", "exec-1",
                null, null, null, null);

        assertThat(state.context()).isEmpty();
        assertThat(state.inputs()).isEmpty();
        assertThat(state.outputs()).isEmpty();
        assertThat(state.metadata()).isEmpty();
    }

    @Test
    void withOutputsReturnsANewInstanceWithEverythingElseUnchanged() {
        WorkflowState state = new WorkflowState(UUID.randomUUID(), UUID.randomUUID(), "wf", "exec-1",
                Map.of("k", "v"), Map.of("in", 1), Map.of(), Map.of());

        WorkflowState updated = state.withOutputs(Map.of("ats_score", 92));

        assertThat(updated.outputs()).containsEntry("ats_score", 92);
        assertThat(updated.missionId()).isEqualTo(state.missionId());
        assertThat(updated.inputs()).isEqualTo(state.inputs());
        assertThat(state.outputs()).isEmpty();
    }
}
