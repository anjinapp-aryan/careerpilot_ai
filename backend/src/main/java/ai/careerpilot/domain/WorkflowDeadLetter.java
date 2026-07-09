package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.0 — a captured workflow failure. Instead of throwing (which the async
 * {@code @TransactionalEventListener} workers must never do), every worker's catch block writes one
 * of these, so a failing stage is isolated, auditable, and replayable by a human without corrupting
 * the running workflow. Append-only.
 */
@Entity
@Table(name = "workflow_dead_letter")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkflowDeadLetter {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "correlation_id") private UUID correlationId;
    @Column(nullable = false) private String workflow;
    @Column(nullable = false) private String stage;
    @Column(columnDefinition = "text") private String payload;
    @Column(columnDefinition = "text") private String exception;
    @Column(name = "retry_count", nullable = false) @Builder.Default private int retryCount = 0;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
