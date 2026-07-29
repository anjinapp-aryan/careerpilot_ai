package ai.careerpilot.repo;

import ai.careerpilot.domain.StrategyPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StrategyPlanRepository extends JpaRepository<StrategyPlan, UUID> {

    /** The plan history for a mission, newest first — this table is its own audit trail. */
    List<StrategyPlan> findByMissionIdOrderByGeneratedAtDesc(UUID missionId);

    Optional<StrategyPlan> findFirstByMissionIdOrderByGeneratedAtDesc(UUID missionId);
}
