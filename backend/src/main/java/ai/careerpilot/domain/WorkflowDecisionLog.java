package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Mission Orchestrator, Phase 5 — one recommended workflow (referenced by its Workflow Registry
 * business key, e.g. "SKILL_ANALYSIS_V1") from a single {@link MissionExecution} run, with the
 * deterministic reason it was recommended.
 */
@Entity
@Table(name = "workflow_decision_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowDecisionLog {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_execution_id", nullable = false) private UUID missionExecutionId;
    @Column(name = "workflow_id", nullable = false) private String workflowId;
    @Column(nullable = false, columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
