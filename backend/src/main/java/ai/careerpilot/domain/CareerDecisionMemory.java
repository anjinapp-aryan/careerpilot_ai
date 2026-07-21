package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.15.1 — one durable career decision/preference signal, extracted automatically from
 * existing domain events (never a new user-facing capture flow). Append-only: a superseding
 * decision (e.g. the candidate changes their mind about relocation) is a NEW row, never an
 * UPDATE, so {@code CareerMemoryService} can reconstruct a timeline and let newer rows naturally
 * outrank older ones in retrieval instead of destroying history. See {@code CareerMemoryService}
 * for the extraction sources and the ranked-retrieval algorithm.
 */
@Entity
@Table(name = "career_decision_memory")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CareerDecisionMemory {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;

    @Column(name = "decision_type", nullable = false) private String decisionType;
    @Column(nullable = false) private String category;
    @Column(columnDefinition = "text") private String value;
    @Column(columnDefinition = "text") private String reason;
    @Builder.Default private BigDecimal confidence = BigDecimal.ONE;
    @Column(nullable = false) private String source;
    @Builder.Default private Short importance = 3;
    @Column(name = "ai_generated") @Builder.Default private Boolean aiGenerated = true;
    @Column(name = "user_confirmed") @Builder.Default private Boolean userConfirmed = false;
    @Column(name = "expires_at") private Instant expiresAt;

    @Column(name = "job_id") private UUID jobId;
    @Column(name = "correlation_id") private UUID correlationId;
    @Column(name = "workflow_id") private String workflowId;

    @Column(name = "usage_count") @Builder.Default private Integer usageCount = 0;
    @Column(name = "last_used_at") private Instant lastUsedAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
