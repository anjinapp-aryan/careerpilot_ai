package ai.careerpilot.mission;

import ai.careerpilot.api.MissionController;
import ai.careerpilot.api.dto.MissionDtos.MissionRequest;
import ai.careerpilot.api.dto.MissionDtos.MissionResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Mission Engine, Phase 1 — {@link MissionController}. Thin: confirms each HTTP verb delegates to
 * the right {@link MissionService} call with the authenticated user's own id (never a
 * client-supplied one) and maps the entity back through {@link MissionResponse#from}.
 */
class MissionControllerTest {

    private final MissionService service = mock(MissionService.class);
    private final MissionController controller = new MissionController(service);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "OWNER");

    private CareerMission mission(UUID id) {
        return CareerMission.builder().id(id).userId(user.userId())
                .missionStatement("stmt").targetRole("role").status(MissionStatus.ACTIVE).build();
    }

    private MissionRequest request() {
        return new MissionRequest("stmt", "role", null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createDelegatesToServiceWithAuthenticatedUserId() {
        CareerMission created = mission(UUID.randomUUID());
        when(service.create(eq(user.userId()), any())).thenReturn(created);

        MissionResponse response = controller.create(user, request());

        assertThat(response.id()).isEqualTo(created.getId());
        verify(service).create(user.userId(), request());
    }

    @Test
    void getDelegatesWithAuthenticatedUserIdNotAClientSuppliedOne() {
        UUID id = UUID.randomUUID();
        when(service.get(user.userId(), id)).thenReturn(mission(id));

        MissionResponse response = controller.get(user, id);

        assertThat(response.id()).isEqualTo(id);
        verify(service).get(user.userId(), id);
    }

    @Test
    void listReturnsAllOfTheUsersMissionsMappedToResponses() {
        when(service.list(user.userId())).thenReturn(List.of(mission(UUID.randomUUID()), mission(UUID.randomUUID())));

        List<MissionResponse> responses = controller.list(user);

        assertThat(responses).hasSize(2);
    }

    @Test
    void updateDelegatesToServiceAndReturnsMappedResponse() {
        UUID id = UUID.randomUUID();
        when(service.update(eq(user.userId()), eq(id), any())).thenReturn(mission(id));

        MissionResponse response = controller.update(user, id, request());

        assertThat(response.id()).isEqualTo(id);
        verify(service).update(user.userId(), id, request());
    }

    @Test
    void deleteDelegatesToServiceWithAuthenticatedUserId() {
        UUID id = UUID.randomUUID();

        controller.delete(user, id);

        verify(service).delete(user.userId(), id);
    }
}
