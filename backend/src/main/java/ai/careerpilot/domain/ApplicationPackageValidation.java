package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.11 — immutable, append-only record of one validation run over an {@link ApplicationPackage}
 * version. Never updated in place: a re-validation inserts a new row, preserving the full lineage of
 * READY / HUMAN_REVIEW / BLOCKED verdicts for audit and comparison. {@code checks} is the compact JSON
 * array of individual gate results produced by {@code ApplicationPackageValidator}.
 */
@Entity
@Table(name = "application_package_validation")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationPackageValidation {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_HUMAN_REVIEW = "HUMAN_REVIEW";
    public static final String STATUS_BLOCKED = "BLOCKED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "package_version", nullable = false) private Integer packageVersion;

    @Column(nullable = false) private String status;
    @Column(name = "blocking_reason", columnDefinition = "text") private String blockingReason;
    @Column(columnDefinition = "text") private String checks;
    @Column(name = "correlation_id") private UUID correlationId;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
