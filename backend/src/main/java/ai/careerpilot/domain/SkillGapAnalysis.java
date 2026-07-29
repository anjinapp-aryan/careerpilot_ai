package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 10 — Skill Gap Intelligence Workflow. One row per run: persistence + append-only history
 * (a new run is always a new row, same "generate always inserts" convention as {@code
 * strategy_plan}, Strategy Engine Phase 3). {@link #resultJson} holds the full structured JSON
 * response from the Python AI Execution Plane verbatim once {@code status=SUCCEEDED} — Java never
 * parses or re-derives its fields, only stores and returns it (no AI reasoning in the Control
 * Plane, per this phase's architecture).
 */
@Entity
@Table(name = "skill_gap_analysis")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SkillGapAnalysis {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "mission_id", nullable = false) private UUID missionId;
    @Column(name = "user_id", nullable = false) private UUID userId;

    @Column(name = "workflow_id", nullable = false) private String workflowId;
    @Column(name = "execution_id", nullable = false, unique = true) private String executionId;
    @Column(name = "correlation_id") private String correlationId;

    @Column(nullable = false, length = 20) @Builder.Default
    private String status = "QUEUED";

    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
