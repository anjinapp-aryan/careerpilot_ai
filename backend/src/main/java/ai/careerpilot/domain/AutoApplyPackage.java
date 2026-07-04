package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.7 — deterministic readiness assessment of an assembled {@link ApplicationPackage}:
 * how the job would be applied to and whether that can be done safely without a human.
 * PREPARATION ONLY — nothing in the codebase performs the application. Append-only.
 */
@Entity
@Table(name = "auto_apply_package")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AutoApplyPackage {

    public static final String METHOD_EXTERNAL_URL = "EXTERNAL_URL";
    public static final String METHOD_EMAIL = "EMAIL";
    public static final String METHOD_UNKNOWN = "UNKNOWN";

    public static final String STATUS_SAFE_TO_APPLY = "SAFE_TO_APPLY";
    public static final String STATUS_REQUIRES_REVIEW = "REQUIRES_REVIEW";
    public static final String STATUS_MANUAL_ONLY = "MANUAL_ONLY";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "application_id") private UUID applicationId;

    private String provider;
    @Column(name = "application_method", nullable = false) private String applicationMethod;
    @Column(name = "requires_login", nullable = false) private Boolean requiresLogin;
    @Column(name = "requires_questionnaire", nullable = false) private Boolean requiresQuestionnaire;
    @Column(name = "requires_upload", nullable = false) private Boolean requiresUpload;
    @Column(name = "readiness_score", nullable = false) private Integer readinessScore;
    @Column(nullable = false) private String status;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
