package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/** Phase 7.12 — one immutable, append-only AI review run (history behind {@link ApplicationReview}). */
@Entity
@Table(name = "application_review_history")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationReviewHistory {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_review_id", nullable = false) private UUID applicationReviewId;
    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "package_version", nullable = false) private Integer packageVersion;
    @Column(name = "review_version", nullable = false) private Integer reviewVersion;

    @Column(name = "resume_score") private Integer resumeScore;
    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "company_fit_score") private Integer companyFitScore;
    @Column(name = "learning_confidence") private Integer learningConfidence;
    @Column(name = "consistency_status") private String consistencyStatus;
    @Column(name = "quality_score") private Integer qualityScore;
    @Column(name = "quality_category") private String qualityCategory;

    @Column(nullable = false) private String verdict;
    @Column private Integer confidence;
    @Column(columnDefinition = "text") private String reasons;
    @Column(name = "correlation_id") private UUID correlationId;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
