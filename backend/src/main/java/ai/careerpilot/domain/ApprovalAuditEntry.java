package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2E.4 — append-only audit trail for every approval-queue decision. */
@Entity
@Table(name = "approval_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalAuditEntry {

    public static final String OUTCOME_ENQUEUED = "ENQUEUED";
    public static final String OUTCOME_APPROVED = "APPROVED";
    public static final String OUTCOME_REJECTED = "REJECTED";
    public static final String OUTCOME_EXPIRED = "EXPIRED";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "approval_id") private UUID approvalId;
    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
