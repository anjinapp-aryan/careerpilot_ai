package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerMission;
import ai.careerpilot.mission.MissionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CareerMissionRepository extends JpaRepository<CareerMission, UUID> {

    List<CareerMission> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<CareerMission> findByIdAndUserId(UUID id, UUID userId);

    /** Country Intelligence Engine, Phase 2 — the mission career-fit defaults against when no missionId is given. */
    Optional<CareerMission> findFirstByUserIdAndStatusOrderByCreatedAtDesc(UUID userId, MissionStatus status);
}
