package ai.careerpilot.api.dto;

import ai.careerpilot.api.dto.MissionDtos.MissionRequest;
import ai.careerpilot.api.dto.MissionDtos.MissionResponse;
import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.mission.MissionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Mission Engine, Phase 1 — the {@code MissionRequest}/{@code MissionResponse} <-> entity mapping. */
class MissionDtosTest {

    @Test
    void requestToEntityRoundTripsListFieldsThroughJson() {
        UUID userId = UUID.randomUUID();
        MissionRequest request = new MissionRequest(
                "Become Principal Java AI Architect in Europe within 24 months",
                "Principal Java AI Architect", "PRINCIPAL",
                List.of("Technology"), List.of("Germany", "Netherlands"),
                new BigDecimal("120000"), new BigDecimal("160000"), "EUR",
                24, "IC track", List.of("Kubernetes"), List.of("Java"), "Ambition text",
                MissionStatus.ACTIVE);

        CareerMission entity = request.toEntity(userId);
        MissionResponse response = MissionResponse.from(entity);

        assertThat(response.userId()).isEqualTo(userId);
        assertThat(response.targetCountries()).containsExactly("Germany", "Netherlands");
        assertThat(response.skillsToAcquire()).containsExactly("Kubernetes");
        assertThat(response.currentSkills()).containsExactly("Java");
        assertThat(response.status()).isEqualTo(MissionStatus.ACTIVE);
        assertThat(response.salaryExpectationMin()).isEqualByComparingTo("120000");
    }

    @Test
    void nullListFieldsMapToEmptyListsNotNull() {
        CareerMission entity = new MissionRequest(
                "stmt", "role", null, null, null, null, null, null, null, null, null, null, null, null)
                .toEntity(UUID.randomUUID());

        MissionResponse response = MissionResponse.from(entity);

        assertThat(response.targetCountries()).isEmpty();
        assertThat(response.targetIndustries()).isEmpty();
        assertThat(response.skillsToAcquire()).isEmpty();
        assertThat(response.currentSkills()).isEmpty();
    }

    @Test
    void applyToOverwritesEveryMutableFieldButPreservesStatusWhenNull() {
        CareerMission existing = CareerMission.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID())
                .missionStatement("old").targetRole("old role").status(MissionStatus.COMPLETED)
                .build();
        MissionRequest request = new MissionRequest(
                "new statement", "new role", "STAFF", List.of("Finance"), List.of("Canada"),
                null, null, null, 12, "Direction", null, null, null, null);

        request.applyTo(existing);

        assertThat(existing.getMissionStatement()).isEqualTo("new statement");
        assertThat(existing.getTargetRole()).isEqualTo("new role");
        assertThat(existing.getTargetLevel()).isEqualTo("STAFF");
        assertThat(existing.getStatus()).isEqualTo(MissionStatus.COMPLETED); // null status in request -> unchanged
    }
}
