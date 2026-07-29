package ai.careerpilot.api;

import ai.careerpilot.api.dto.StrategyDtos.GenerateStrategyRequest;
import ai.careerpilot.api.dto.StrategyDtos.StrategyActionResponse;
import ai.careerpilot.api.dto.StrategyDtos.StrategyPlanResponse;
import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.strategy.StrategyEvaluationService;
import ai.careerpilot.strategy.StrategyEvaluationService.GeneratedStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Strategy Engine, Phase 3 — {@link StrategyController}. */
class StrategyControllerTest {

    private final StrategyEvaluationService service = mock(StrategyEvaluationService.class);
    private final StrategyController controller = new StrategyController(service);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "OWNER");

    private GeneratedStrategy strategy(UUID missionId) {
        StrategyPlan plan = StrategyPlan.builder().id(UUID.randomUUID()).missionId(missionId)
                .timeframeDays(90).status(MissionStatus.ACTIVE).build();
        CareerGoal action = CareerGoal.builder().id(UUID.randomUUID()).missionId(missionId)
                .title("Improve Kubernetes knowledge").status(MissionStatus.ACTIVE).build();
        return new GeneratedStrategy(plan, List.of(action));
    }

    @Test
    void generateDelegatesToServiceWithAuthenticatedUserId() {
        UUID missionId = UUID.randomUUID();
        when(service.generate(user.userId(), missionId)).thenReturn(strategy(missionId));

        StrategyPlanResponse response = controller.generate(user, new GenerateStrategyRequest(missionId));

        assertThat(response.missionId()).isEqualTo(missionId);
        assertThat(response.actions()).hasSize(1);
        verify(service).generate(user.userId(), missionId);
    }

    @Test
    void latestDelegatesToServiceWithAuthenticatedUserId() {
        UUID missionId = UUID.randomUUID();
        when(service.latest(user.userId(), missionId)).thenReturn(strategy(missionId));

        StrategyPlanResponse response = controller.latest(user, missionId);

        assertThat(response.missionId()).isEqualTo(missionId);
        verify(service).latest(user.userId(), missionId);
    }

    @Test
    void completeActionDelegatesToServiceWithAuthenticatedUserId() {
        UUID actionId = UUID.randomUUID();
        CareerGoal completed = CareerGoal.builder().id(actionId).missionId(UUID.randomUUID())
                .title("Improve Kubernetes knowledge").status(MissionStatus.COMPLETED).build();
        when(service.completeAction(user.userId(), actionId)).thenReturn(completed);

        StrategyActionResponse response = controller.completeAction(user, actionId);

        assertThat(response.status()).isEqualTo(MissionStatus.COMPLETED);
        verify(service).completeAction(user.userId(), actionId);
    }
}
