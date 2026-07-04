package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2E.1 — append-only audit trail for every application-execution state transition. */
@Entity
@Table(name = "application_execution_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationExecutionAuditEntry {

    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_execution_id") private UUID applicationExecutionId;
    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
