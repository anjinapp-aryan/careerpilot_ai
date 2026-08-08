package ai.careerpilot.repo;

import ai.careerpilot.domain.ExecutionStageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * P5 — read access to the append-only stage timeline.
 *
 * <p>There is deliberately no delete method: the append-only contract is enforced by the absence
 * of a way to violate it, the same convention as {@code AtsValidationRunRepository}. Aggregate
 * finders are bounded or grouped so a dashboard read can never pull an unbounded series into a
 * 1-vCPU box's heap.
 */
public interface ExecutionStageEventRepository extends JpaRepository<ExecutionStageEvent, UUID> {

    /** One execution's timeline, in the order the stages actually happened. */
    List<ExecutionStageEvent> findByExecutionIdOrderBySequenceNoAsc(UUID executionId);

    /** The open row for a stage, so the recorder can close the one it opened. */
    Optional<ExecutionStageEvent> findFirstByExecutionIdAndStageAndStatusOrderBySequenceNoDesc(
            UUID executionId, String stage, String status);

    /** Next sequence number for an execution. Null when the execution has no events yet. */
    @Query("select max(e.sequenceNo) from ExecutionStageEvent e where e.executionId = :executionId")
    Integer maxSequenceNo(@Param("executionId") UUID executionId);

    /**
     * Average and count of completed durations per stage, for the metrics dashboard.
     * Grouped in SQL rather than pulled into memory — this is the query a "which stage is slow"
     * panel runs, and it must not scale with the number of rows.
     */
    @Query("""
            select e.stage, avg(e.durationMs), count(e)
            from ExecutionStageEvent e
            where e.status = 'COMPLETED' and e.durationMs is not null and e.createdAt >= :since
            group by e.stage
            """)
    List<Object[]> averageDurationByStageSince(@Param("since") Instant since);

    /** Failure counts per (stage, category) — the "top failure stage / reason" panel. */
    @Query("""
            select e.stage, e.failureCategory, count(e)
            from ExecutionStageEvent e
            where e.status = 'FAILED' and e.createdAt >= :since
            group by e.stage, e.failureCategory
            order by count(e) desc
            """)
    List<Object[]> failureCountsSince(@Param("since") Instant since);
}
