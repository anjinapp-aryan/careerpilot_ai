package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.12 — the HEAD AI review per Application Package: the final quality gate's verdict plus each
 * reviewer's score. It REVIEWS the Phase 7.11 package (resume/ATS/recommendation/company/learning
 * assessments) and never mutates any of them. {@link ApplicationReviewHistory} keeps the immutable
 * append-only trail of every review run.
 */
@Entity
@Table(name = "application_review")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationReview {

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "application_package_id", nullable = false) private UUID applicationPackageId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "package_version", nullable = false) private Integer packageVersion;

    @Column(name = "resume_score") private Integer resumeScore;
    @Column(name = "ats_score") private Integer atsScore;
    @Column(name = "company_fit_score") private Integer companyFitScore;
    @Column(name = "learning_confidence") private Integer learningConfidence;
    @Column(name = "consistency_status") private String consistencyStatus; // PASS | WARNING | FAIL
    @Column(name = "quality_score") private Integer qualityScore;          // 0-100
    @Column(name = "quality_category") private String qualityCategory;     // EXCELLENT | STRONG | GOOD | WEAK | BLOCKED

    @Column(nullable = false) private String verdict; // READY | HUMAN_REVIEW | BLOCKED
    @Column private Integer confidence;               // 0-100
    @Column(columnDefinition = "text") private String reasons;
    @Column(name = "correlation_id") private UUID correlationId;
    @Column(name = "review_version", nullable = false) private Integer reviewVersion;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
