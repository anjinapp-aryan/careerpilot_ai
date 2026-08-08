package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationExecutionRepository extends JpaRepository<ApplicationExecution, UUID> {

    Optional<ApplicationExecution> findByIdAndUserId(UUID id, UUID userId);

    Optional<ApplicationExecution> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /**
     * Bulk form of {@link #findFirstByUserIdAndJobIdOrderByCreatedAtDesc} — the latest execution per
     * job for a whole page of cards, in one query. {@code DISTINCT ON} keeps the first row of each
     * {@code job_id} group under the same ordering the per-row finder uses, so the two agree by
     * construction.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DISTINCT ON (job_id) *
            FROM application_execution
            WHERE user_id = :userId AND job_id IN (:jobIds)
            ORDER BY job_id, created_at DESC
            """, nativeQuery = true)
    List<ApplicationExecution> findLatestPerJob(
            @org.springframework.data.repository.query.Param("userId") UUID userId,
            @org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);

    /**
     * Atomically claims an approved execution for submission: {@code AWAITING_APPROVAL -> SUBMITTING}.
     *
     * <p>Exists because the previous guard was a check-then-act — read the row, compare the status,
     * then drive the browser. Under Postgres READ COMMITTED two workers (an at-least-once approval
     * event, a worker retry, the manual retry endpoint) could both read {@code AWAITING_APPROVAL}
     * and both submit. The single browser lease serialises them, so they submit one after the other
     * — <b>both for real</b>. A conditional UPDATE makes the transition the claim itself: the row is
     * no longer {@code AWAITING_APPROVAL} after the first winner, so every later caller sees zero
     * rows affected and stops.
     *
     * <p><b>P7 Action 1 — {@code @Transactional} is load-bearing here, not decorative.</b> A live
     * Testcontainers/Postgres reproduction proved that without it, every call from {@code
     * ApplicationExecutionService.finalizeGuestApplySubmit} (deliberately not {@code @Transactional}
     * itself, and called from a bounded executor thread with no ambient transaction) threw {@code
     * jakarta.persistence.TransactionRequiredException: No active transaction for update or delete
     * query} — caught by that method's own catch block, logged, and silently returned. The entire
     * guest-apply real-submit path was therefore never reaching a claim at all: not "sometimes
     * double-submits," but "never submits." Spring Data's repository proxy does not synthesize a
     * default transaction for a bare {@code @Modifying} query method; it only ever finds one if
     * {@code @Transactional} is actually declared somewhere on the method/class chain. Declaring it
     * directly on this method (rather than requiring the caller to be {@code @Transactional}) keeps
     * the transaction scoped to exactly this one UPDATE, matching the original design intent
     * described above — the browser work that follows the claim in {@code finalizeGuestApplySubmit}
     * still runs with no open transaction.
     *
     * @return 1 when this caller won the claim, 0 when someone else already has it
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE application_execution
            SET execution_status = 'SUBMITTING'
            WHERE id = :id AND execution_status = 'AWAITING_APPROVAL'
            """, nativeQuery = true)
    int claimForSubmit(@org.springframework.data.repository.query.Param("id") UUID id);

    long countByExecutionStatus(String executionStatus);

    /** Phase 7.16.3 — the Automation Recovery Center's retry queue: due RETRY rows. */
    List<ApplicationExecution> findByExecutionStatusAndNextRetryAtBefore(String executionStatus, Instant before);

    /**
     * P7 Action 4 — stale rows of any status, by age since creation. Used for {@code SUBMITTING}
     * (a stale row means the process died or is still working between the atomic claim and the
     * terminal write — see {@link ApplicationExecution#STATUS_SUBMITTING}'s own javadoc), but
     * intentionally not named for that one status since the query itself is generic.
     */
    List<ApplicationExecution> findByExecutionStatusAndCreatedAtBefore(String executionStatus, Instant before);

    // ── Phase 7.16.4 — Operations Center aggregation. All additive counts/bounded reads; no new
    // persistence, just query surface for data that already exists on this entity. ──

    /** Cancellations reuse STATUS_ABORTED (see ApplicationExecutionService#cancel) — distinguished only by failureReason prefix. */
    long countByExecutionStatusAndFailureReasonStartingWith(String executionStatus, String failureReasonPrefix);

    /** SUBMITTED rows that reached that state via the Recovery Center (not a first attempt). */
    long countByExecutionStatusAndRetryOfExecutionIdIsNotNull(String executionStatus);

    long countByExecutionStatusAndVerificationStatus(String executionStatus, String verificationStatus);

    long countByExecutionStatusAndVerificationStatusIsNull(String executionStatus);

    Optional<ApplicationExecution> findFirstByExecutionStatusOrderByCreatedAtAsc(String executionStatus);

    Optional<ApplicationExecution> findFirstByExecutionStatusOrderByCreatedAtDesc(String executionStatus);

    /** Fleet View — bounded to the most recent 1000 provider-attributed executions (diagnostics scale, not full history). */
    List<ApplicationExecution> findTop1000ByProviderIsNotNullOrderByCreatedAtDesc();
}
