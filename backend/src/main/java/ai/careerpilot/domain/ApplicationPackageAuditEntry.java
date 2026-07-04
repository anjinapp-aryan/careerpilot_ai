package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2D.6 — append-only audit trail for package assembly attempts. */
@Entity
@Table(name = "application_package_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationPackageAuditEntry {

    public static final String OUTCOME_ASSEMBLED = "ASSEMBLED";
    public static final String OUTCOME_INCOMPLETE = "INCOMPLETE";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_package_id") private UUID applicationPackageId;
    @Column(name = "package_version") private Integer packageVersion;
    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
