package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Workflow Registry, Phase 4 — a thin audit record of one registry-triggered run. <b>Not</b> the
 * same thing as {@link WorkflowRun} (the existing, detailed LangGraph thread-execution tracking
 * entity owned by {@code WorkflowService}) — this table never invokes the agent-service directly;
 * real AI-pipeline execution continues to go exclusively through the existing {@code
 * WorkflowService}. See {@code WorkflowExecutionService} for exactly what status transitions this
 * class currently gets (mostly {@code DEFERRED} — see its own javadoc for the honesty rationale,
 * same discipline as {@code DeferredAgentTaskExecutor}).
 */
@Entity
@Table(name = "workflow_execution")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowExecution {
    @Id @GeneratedValue
    private UUID id;

    @Column(name = "workflow_definition_id", nullable = false) private UUID workflowDefinitionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "mission_id") private UUID missionId;

    @Column(nullable = false, length = 20) @Builder.Default private String status = "QUEUED";
    @Column(columnDefinition = "text") private String notes;

    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
