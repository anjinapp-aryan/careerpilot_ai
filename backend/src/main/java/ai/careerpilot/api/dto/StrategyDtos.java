package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CareerGoal;
import ai.careerpilot.domain.StrategyPlan;
import ai.careerpilot.mission.MissionStatus;
import ai.careerpilot.strategy.StrategyEvaluationService.GeneratedStrategy;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Strategy Engine, Phase 3 — request/response shapes for {@code StrategyController}. */
public final class StrategyDtos {

    private StrategyDtos() {}

    public record GenerateStrategyRequest(@NotNull UUID missionId) {}

    public record StrategyActionResponse(
            UUID id, UUID missionId, String title, String description,
            LocalDate targetDate, MissionStatus status, Instant createdAt
    ) {
        public static StrategyActionResponse from(CareerGoal g) {
            return new StrategyActionResponse(g.getId(), g.getMissionId(), g.getTitle(), g.getDescription(),
                    g.getTargetDate(), g.getStatus(), g.getCreatedAt());
        }
    }

    public record StrategyPlanResponse(
            UUID id, UUID missionId, Integer timeframeDays, MissionStatus status,
            Instant generatedAt, List<StrategyActionResponse> actions
    ) {
        public static StrategyPlanResponse from(GeneratedStrategy strategy) {
            StrategyPlan plan = strategy.plan();
            return new StrategyPlanResponse(plan.getId(), plan.getMissionId(), plan.getTimeframeDays(),
                    plan.getStatus(), plan.getGeneratedAt(),
                    strategy.actions().stream().map(StrategyActionResponse::from).toList());
        }
    }
}
