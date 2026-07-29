package ai.careerpilot.mission;

import ai.careerpilot.api.dto.MissionDtos.MissionRequest;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.repo.CareerMissionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Mission Engine, Phase 1 — {@link MissionService}. Pins ownership scoping (a mission belonging
 * to another user is indistinguishable from a non-existent one — {@link MissionNotFoundException}
 * either way) and the create/update JSON round-trip via {@code JsonLists}.
 */
class MissionServiceTest {

    private final CareerMissionRepository repository = mock(CareerMissionRepository.class);
    private final MissionService service = new MissionService(repository);

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID missionId = UUID.randomUUID();

    private MissionRequest request() {
        return new MissionRequest(
                "Become Principal Java AI Architect in Europe within 24 months",
                "Principal Java AI Architect", "PRINCIPAL",
                List.of("Technology", "Finance"), List.of("Germany", "Netherlands"),
                new BigDecimal("120000"), new BigDecimal("160000"), "EUR",
                24, "Individual Contributor track",
                List.of("Kubernetes", "LangGraph"), List.of("Java", "Spring Boot"),
                "Lead architecture for AI-native platforms at enterprise scale.",
                null);
    }

    private CareerMission existingMission() {
        return CareerMission.builder()
                .id(missionId).userId(userId)
                .missionStatement("old statement").targetRole("old role")
                .status(MissionStatus.ACTIVE)
                .build();
    }

    @Test
    void createBuildsEntityFromRequestAndDefaultsStatusToActive() {
        when(repository.save(any(CareerMission.class))).thenAnswer(inv -> inv.getArgument(0));

        CareerMission saved = service.create(userId, request());

        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getMissionStatement()).contains("Principal Java AI Architect");
        assertThat(saved.getStatus()).isEqualTo(MissionStatus.ACTIVE);
        assertThat(saved.getTargetCountriesJson()).contains("Germany", "Netherlands");
        assertThat(saved.getSkillsToAcquireJson()).contains("Kubernetes", "LangGraph");
    }

    @Test
    void createHonorsExplicitStatusWhenProvided() {
        when(repository.save(any(CareerMission.class))).thenAnswer(inv -> inv.getArgument(0));
        MissionRequest paused = new MissionRequest(
                "stmt", "role", null, null, null, null, null, null, null, null, null, null, null,
                MissionStatus.PAUSED);

        CareerMission saved = service.create(userId, paused);

        assertThat(saved.getStatus()).isEqualTo(MissionStatus.PAUSED);
    }

    @Test
    void getReturnsMissionScopedToOwner() {
        when(repository.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(existingMission()));

        CareerMission found = service.get(userId, missionId);

        assertThat(found.getId()).isEqualTo(missionId);
    }

    @Test
    void getThrowsMissionNotFoundForWrongOwner() {
        // The repo query is itself scoped by userId, so a wrong-owner lookup returns empty —
        // indistinguishable from a genuinely non-existent id, by design (see MissionNotFoundException javadoc).
        when(repository.findByIdAndUserId(missionId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(otherUserId, missionId))
                .isInstanceOf(MissionNotFoundException.class);
    }

    @Test
    void getThrowsMissionNotFoundForNonExistentId() {
        UUID randomId = UUID.randomUUID();
        when(repository.findByIdAndUserId(randomId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(userId, randomId))
                .isInstanceOf(MissionNotFoundException.class);
    }

    @Test
    void updateAppliesRequestOntoExistingEntityAndPreservesId() {
        CareerMission existing = existingMission();
        when(repository.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(CareerMission.class))).thenAnswer(inv -> inv.getArgument(0));

        CareerMission updated = service.update(userId, missionId, request());

        assertThat(updated.getId()).isEqualTo(missionId);
        assertThat(updated.getMissionStatement()).contains("Principal Java AI Architect");
        assertThat(updated.getTargetRole()).isEqualTo("Principal Java AI Architect");
    }

    @Test
    void updateWithNullStatusLeavesExistingStatusUnchanged() {
        CareerMission existing = existingMission();
        existing.setStatus(MissionStatus.PAUSED);
        when(repository.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(CareerMission.class))).thenAnswer(inv -> inv.getArgument(0));

        CareerMission updated = service.update(userId, missionId, request()); // request().status() == null

        assertThat(updated.getStatus()).isEqualTo(MissionStatus.PAUSED);
    }

    @Test
    void deleteRemovesTheOwnedMission() {
        CareerMission existing = existingMission();
        when(repository.findByIdAndUserId(missionId, userId)).thenReturn(Optional.of(existing));

        service.delete(userId, missionId);

        verify(repository).delete(existing);
    }

    @Test
    void deleteThrowsMissionNotFoundRatherThanDeletingAnotherUsersMission() {
        when(repository.findByIdAndUserId(missionId, otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(otherUserId, missionId))
                .isInstanceOf(MissionNotFoundException.class);
        verify(repository, never()).delete(any());
    }

    @Test
    void listReturnsOnlyTheUsersOwnMissions() {
        when(repository.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(existingMission()));

        assertThat(service.list(userId)).hasSize(1);
    }
}
