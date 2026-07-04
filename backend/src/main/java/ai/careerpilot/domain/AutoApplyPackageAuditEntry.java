package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2D.7 — append-only audit trail for auto-apply preparation attempts. */
@Entity
@Table(name = "auto_apply_package_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AutoApplyPackageAuditEntry {

    public static final String OUTCOME_PREPARED = "PREPARED";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "auto_apply_package_id") private UUID autoApplyPackageId;
    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
