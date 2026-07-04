package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 2D.6 — one immutable, append-only package assembly (history behind {@link ApplicationPackage}). */
@Entity
@Table(name = "application_package_versions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationPackageVersion {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
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
}
