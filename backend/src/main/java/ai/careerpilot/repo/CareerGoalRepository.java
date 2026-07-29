package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerGoal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CareerGoalRepository extends JpaRepository<CareerGoal, UUID> {

    List<CareerGoal> findByMissionIdOrderByCreatedAtDesc(UUID missionId);

    /** Strategy Engine, Phase 3 — the concrete actions belonging to one generated plan. */
    List<CareerGoal> findByStrategyPlanIdOrderByCreatedAtAsc(UUID strategyPlanId);
}
