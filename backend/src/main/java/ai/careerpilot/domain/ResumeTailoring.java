package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.1 — a per-job, LLM-tailored rewrite of a user's original {@link Resume}. The original
 * resume ({@code s3Key}/{@code parsedText}) is never modified; each generation is a new immutable
 * row with an incrementing {@link #tailoringVersion} scoped to (userId, jobId), rendered as
 * "v1.N" at the API/DTO layer (see the module's package Javadoc for the versioning decision).
 *
 * <p>Lineage columns ({@code recommendationAuditId}, {@code candidateProfileVersion},
 * {@code behaviorProfileVersion}) capture exactly which scoring/profile/behavior snapshot this
 * tailoring was generated against, for reproducibility and future auto-apply audit trails.
 */
@Entity
@Table(name = "resume_tailoring")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeTailoring {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_APPROVED = "APPROVED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "original_resume_id", nullable = false) private UUID originalResumeId;

    @Column(name = "recommendation_audit_id") private UUID recommendationAuditId;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "behavior_profile_version") private Instant behaviorProfileVersion;

    @Column(name = "tailoring_version", nullable = false) private Integer tailoringVersion;

    @Column(name = "tailored_resume_text", nullable = false, columnDefinition = "text")
    private String tailoredResumeText;
    @Column(name = "tailored_resume_s3_key") private String tailoredResumeS3Key;

    @Column(name = "ats_before") private Integer atsBefore;
    @Column(name = "ats_after") private Integer atsAfter;
    @Column(name = "improvement_score") private Integer improvementScore;
    @Column(name = "confidence_score") private BigDecimal confidenceScore;

    @Column(nullable = false) private String status;
    @Column(name = "model_used") private String modelUsed;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
