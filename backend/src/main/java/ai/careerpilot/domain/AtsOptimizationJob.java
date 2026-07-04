package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2D.2 — tracks one async execution of {@code AtsOptimizationService.analyze()} on the
 * bounded {@code atsOptimizationExecutor}, mirroring {@link ResumeTailoringJob}'s lifecycle
 * exactly: created {@code QUEUED}, {@code RUNNING} while the executor works, terminal
 * {@code SUCCEEDED} (with {@link #atsAnalysisId} set) or {@code FAILED} (with {@link
 * #errorReason} set). Populated either from the manual API ({@code source=MANUAL}) or from
 * {@code AtsOptimizationWorker} reacting to a {@code ResumeTailoredEvent}
 * ({@code source=TAILORING_TRIGGER}).
 */
@Entity
@Table(name = "ats_optimization_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AtsOptimizationJob {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_TAILORING_TRIGGER = "TAILORING_TRIGGER";

    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @Id @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id", nullable = false) private UUID jobId;
    @Column(name = "resume_tailoring_id", nullable = false) private UUID resumeTailoringId;
    @Column(name = "ats_analysis_id") private UUID atsAnalysisId;

    @Column(nullable = false) private String source;
    @Column(nullable = false) private String status;
    @Column(name = "error_reason", columnDefinition = "text") private String errorReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
}
