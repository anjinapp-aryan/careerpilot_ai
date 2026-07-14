package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.16 — one user-triggered "Apply" click, tracked end-to-end as a single unit: job
 * validation -&gt; package/review/company/STAR readiness -&gt; field mapping -&gt; question
 * detection/answering -&gt; submission-strategy resolution -&gt; optional human approval -&gt;
 * execution -&gt; verification -&gt; tracking -&gt; learning. Links (by id, never by copy) into the
 * existing dormant {@code execution}/{@code packageintel}/{@code review}/{@code companyintel}/
 * {@code story} rows it orchestrates — this entity never duplicates their content.
 *
 * <p>Ships DARK: flag-gated by {@code application.submission.enabled} (default false). Every
 * status transition is validated by {@link ai.careerpilot.submission.SubmissionStateMachine}.
 */
@Entity
@Table(name = "application_submission_session")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApplicationSubmissionSession {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_VALIDATING = "VALIDATING";
    public static final String STATUS_PACKAGE_READY = "PACKAGE_READY";
    public static final String STATUS_REVIEW_READY = "REVIEW_READY";
    public static final String STATUS_COMPANY_READY = "COMPANY_READY";
    public static final String STATUS_STAR_READY = "STAR_READY";
    public static final String STATUS_READY_FOR_SUBMISSION = "READY_FOR_SUBMISSION";
    public static final String STATUS_WAITING_APPROVAL = "WAITING_APPROVAL";
    public static final String STATUS_SUBMITTING = "SUBMITTING";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    public static final String STATUS_VERIFIED = "VERIFIED";
    public static final String STATUS_TRACKING = "TRACKING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String METHOD_AUTO = "AUTO";
    public static final String METHOD_MANUAL = "MANUAL";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "application_id") private UUID applicationId;
    @Column(name = "resume_id") private UUID resumeId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "application_package_id") private UUID applicationPackageId;
    @Column(name = "application_review_id") private UUID applicationReviewId;
    @Column(name = "company_knowledge_id") private UUID companyKnowledgeId;
    @Column(name = "approval_queue_entry_id") private UUID approvalQueueEntryId;
    @Column(name = "application_execution_id") private UUID applicationExecutionId;

    @Column(nullable = false) private String status;
    @Builder.Default
    @Column(name = "submission_method", nullable = false) private String submissionMethod = METHOD_MANUAL;
    private String provider;
    @Column(name = "correlation_id") private UUID correlationId;
    @Column(name = "failure_reason", columnDefinition = "text") private String failureReason;

    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private Instant updatedAt;
}
