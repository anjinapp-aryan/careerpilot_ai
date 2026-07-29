package ai.careerpilot.repo;

import ai.careerpilot.domain.MissionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface MissionExecutionRepository extends JpaRepository<MissionExecution, UUID> {

    Optional<MissionExecution> findFirstByMissionIdOrderByRanAtDesc(UUID missionId);
}
