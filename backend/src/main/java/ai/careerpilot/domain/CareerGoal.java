package ai.careerpilot.domain;

import ai.careerpilot.mission.MissionStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — a milestone toward a {@link CareerMission}. <b>Strategy is
 * dynamic</b>: unlike the mission itself, goals are expected to be added, reprioritized, and
 * completed frequently as the user's actual path unfolds.
 *
 * <p>Doubles as the Strategy Engine's (Phase 3) action-item representation — a goal already has
 * exactly the shape a "strategy action" needs (title, completable status), so Phase 3 reuses this
 * entity rather than introducing a near-duplicate {@code strategy_action} table. {@link
 * #strategyPlanId} is null for a goal added ad hoc (Phase 1's original use case) and set when a
 * goal was generated as part of a {@link StrategyPlan} (Phase 3).
 */
@Entity
@Table(name = "career_goal")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerGoal {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_id", nullable = false) private UUID missionId;
    @Column(name = "strategy_plan_id") private UUID strategyPlanId;

    @Column(nullable = false) private String title;
    @Column(columnDefinition = "text") private String description;
    @Column(name = "target_date") private LocalDate targetDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private MissionStatus status = MissionStatus.ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
