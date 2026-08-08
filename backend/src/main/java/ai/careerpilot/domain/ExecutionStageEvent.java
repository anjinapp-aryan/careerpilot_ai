package ai.careerpilot.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * P5 — one stage transition of one application-execution run.
 *
 * <p>The grain nothing else in this platform had: {@code application_execution.checkpoint} keeps
 * only the furthest phase reached (overwritten, untimed), {@code application_execution_audit} is
 * keyed on outcome rather than stage and carries no timing, and Phase 3A's
 * {@code application_timeline} is per (user, job) rather than per execution run. Together those
 * can say an execution failed; only this can say where, how long each part took, and why that
 * stage was the last one.
 *
 * <p><b>Append-only.</b> A row is inserted when a stage opens and updated exactly once to close it
 * ({@code endedAt}/{@code durationMs}/{@code status}/{@code reason}). Nothing rewrites a closed
 * row and the repository exposes no delete — the same contract as {@code AtsValidationRun}.
 */
@Entity
@Table(name = "execution_stage_event")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExecutionStageEvent {

    /** In flight — opened and not yet closed. A run's last STARTED row is where it currently is. */
    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    /** Genuinely not applicable to this run (e.g. no cover letter to upload). Not a failure. */
    public static final String STATUS_SKIPPED = "SKIPPED";

    @Id @GeneratedValue private UUID id;

    @Column(name = "execution_id", nullable = false) private UUID executionId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "job_id") private UUID jobId;

    /**
     * Monotonic per execution. Two stages can land in the same millisecond on a fast path, so
     * ordering is explicit rather than inferred from {@code startedAt} — a timeline that reorders
     * itself under load is worse than no timeline.
     */
    @Column(name = "sequence_no", nullable = false) private int sequenceNo;

    @Column(name = "stage", nullable = false) private String stage;
    @Column(name = "status", nullable = false) private String status;

    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "ended_at") private Instant endedAt;
    @Column(name = "duration_ms") private Long durationMs;

    /** Only set on FAILED — one of the fixed {@code FailureCategory} values, so failures aggregate. */
    @Column(name = "failure_category") private String failureCategory;

    @Column(name = "reason", columnDefinition = "text") private String reason;

    /** Small stage-specific metadata: question counts, confidence, lease id, page title. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", columnDefinition = "jsonb")
    private String detail;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private Instant createdAt;
}
