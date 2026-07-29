package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Mission Orchestrator, Phase 5 — one row per {@code MissionOrchestratorService.run} invocation.
 * {@code decisionSummary} is a short human-readable recap; the actual per-workflow recommendations
 * live in {@link WorkflowDecisionLog} rows keyed by this execution's id.
 */
@Entity
@Table(name = "mission_execution")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MissionExecution {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_id", nullable = false) private UUID missionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "decision_summary", columnDefinition = "text") private String decisionSummary;
    @Column(name = "resume_score_at_run") private Integer resumeScoreAtRun;
    @Column(name = "ran_at") private Instant ranAt;
}
