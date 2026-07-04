package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.0 — one row per running workflow instance, keyed by {@code correlationId}. Upserted on
 * every stage transition so the whole Applied → Offer path is traceable end-to-end and can be joined
 * to {@link WorkflowDeadLetter} rows on the same correlation id.
 */
@Entity
@Table(name = "workflow_correlation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowCorrelation {

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD_LETTERED = "DEAD_LETTERED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "correlation_id", nullable = false, unique = true) private UUID correlationId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id") private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(name = "workflow_type", nullable = false) private String workflowType;
    @Column(name = "workflow_stage", nullable = false) private String workflowStage;
    @Column(nullable = false) private String status;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
