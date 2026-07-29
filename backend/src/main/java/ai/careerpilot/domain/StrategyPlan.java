package ai.careerpilot.domain;

import ai.careerpilot.mission.MissionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Strategy Engine, Phase 3 — converts a {@link CareerMission} into an actionable plan over a
 * timeframe (e.g. 90 days). Its {@link CareerGoal} rows (linked via {@code strategyPlanId}) are
 * the plan's concrete actions. Deliberately append-only: {@code
 * StrategyEvaluationService#generate} always inserts a new row rather than updating one in place,
 * so this table doubles as its own history — no separate {@code strategy_history} table.
 */
@Entity
@Table(name = "strategy_plan")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class StrategyPlan {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_id", nullable = false) private UUID missionId;
    @Column(name = "timeframe_days", nullable = false) private Integer timeframeDays;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MissionStatus status = MissionStatus.ACTIVE;

    @Column(name = "generated_at") private Instant generatedAt;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
