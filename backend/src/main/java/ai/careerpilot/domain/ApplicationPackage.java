package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.6 — the HEAD application package per (user, job): references (not copies) of every
 * artifact version it was assembled from — tailored resume, cover letter, ATS analysis, gap
 * analysis, explainability — plus full profile/behavior/recommendation lineage. {@code metadata}
 * holds the assembled summary JSON. Status {@code ASSEMBLED} when all core artifacts were present,
 * {@code INCOMPLETE} when assembled with gaps (metadata says which).
 */
@Entity
@Table(name = "application_package")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationPackage {

    public static final String STATUS_ASSEMBLED = "ASSEMBLED";
    public static final String STATUS_INCOMPLETE = "INCOMPLETE";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(name = "resume_id") private UUID resumeId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "cover_letter_id") private UUID coverLetterId;
    @Column(name = "ats_analysis_id") private UUID atsAnalysisId;
    @Column(name = "gap_analysis_id") private UUID gapAnalysisId;
    @Column(name = "ats_explanation_id") private UUID atsExplanationId;
    @Column(name = "candidate_profile_version") private UUID candidateProfileVersion;
    @Column(name = "behavior_profile_version") private Instant behaviorProfileVersion;
    @Column(name = "recommendation_audit_id") private UUID recommendationAuditId;

    @Column(name = "package_version", nullable = false) private Integer packageVersion;
    @Column(nullable = false) private String status;
    @Column(columnDefinition = "text") private String metadata;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
