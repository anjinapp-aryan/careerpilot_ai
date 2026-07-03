package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.1.1 — tracks one async execution of {@code ResumeTailoringService.tailor()}/
 * {@code .rebuild()} on the bounded {@code resumeTailoringExecutor}. Created {@code QUEUED} by
 * {@code ResumeTailoringJobService.enqueue()}, transitions to {@code RUNNING} when the executor
 * picks it up, and to a terminal {@code SUCCEEDED} (with {@link #resumeTailoringId} set) or
 * {@code FAILED} (with {@link #errorReason} set) when the underlying call returns. Both the
 * manual API path and the approve-triggered {@code ResumeTailoringWorker} path go through this
 * table, so queue/health diagnostics see a single, unified view of the engine's async work.
 */
@Entity
@Table(name = "resume_tailoring_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ResumeTailoringJob {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_APPROVE_TRIGGER = "APPROVE_TRIGGER";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id") private UUID resumeTailoringId;
    @Column(name = "recommendation_audit_id") private UUID recommendationAuditId;

    @Column(nullable = false) private String source;
    @Column(nullable = false) private String status;
    @Column(name = "error_reason", columnDefinition = "text") private String errorReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
