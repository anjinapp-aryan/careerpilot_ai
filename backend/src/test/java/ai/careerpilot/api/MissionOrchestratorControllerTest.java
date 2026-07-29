package ai.careerpilot.api;

import ai.careerpilot.api.dto.MissionOrchestratorDtos.MissionExecutionResponse;
import ai.careerpilot.domain.MissionExecution;
import ai.careerpilot.mission.MissionOrchestratorService;
import ai.careerpilot.mission.MissionOrchestratorService.Decision;
import ai.careerpilot.mission.MissionOrchestratorService.OrchestrationResult;
import ai.careerpilot.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Mission Orchestrator, Phase 5 — {@link MissionOrchestratorController}. */
class MissionOrchestratorControllerTest {

    private final MissionOrchestratorService service = mock(MissionOrchestratorService.class);
    private final MissionOrchestratorController controller = new MissionOrchestratorController(service);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "OWNER");

    @Test
    void runDelegatesToServiceWithAuthenticatedUserId() {
        UUID missionId = UUID.randomUUID();
        MissionExecution execution = MissionExecution.builder().id(UUID.randomUUID()).missionId(missionId).build();
        OrchestrationResult result = new OrchestrationResult(execution, List.of(new Decision("SKILL_ANALYSIS_V1", "reason")));
        when(service.run(user.userId(), missionId)).thenReturn(result);

        MissionExecutionResponse response = controller.run(user, missionId);

        assertThat(response.decisions()).hasSize(1);
        verify(service).run(user.userId(), missionId);
    }

    @Test
    void statusThrowsNotFoundWhenOrchestratorNeverRan() {
        UUID missionId = UUID.randomUUID();
        when(service.status(user.userId(), missionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.status(user, missionId))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
