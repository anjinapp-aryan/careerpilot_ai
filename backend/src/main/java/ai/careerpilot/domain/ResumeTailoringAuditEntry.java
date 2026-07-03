package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.1 — append-only audit trail for {@link ResumeTailoring} generation attempts, including
 * ones that never produced a row (validation rejection, cache hit, error). Mirrors the
 * {@code recommendation_audit} / {@code recommendation_feedback} append-only convention.
 */
@Entity
@Table(name = "resume_tailoring_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeTailoringAuditEntry {

    public static final String OUTCOME_GENERATED = "GENERATED";
    public static final String OUTCOME_VALIDATION_REJECTED = "VALIDATION_REJECTED";
    public static final String OUTCOME_CACHE_HIT = "CACHE_HIT";
    public static final String OUTCOME_ERROR = "ERROR";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "tailoring_version") private Integer tailoringVersion;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "recommendation_audit_id") private UUID recommendationAuditId;
    @Column(name = "improvement_score") private Integer improvementScore;

    @Column(nullable = false) private String outcome;
    @Column(columnDefinition = "text") private String reason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
