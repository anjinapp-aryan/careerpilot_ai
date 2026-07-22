package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationExecutionRepository extends JpaRepository<ApplicationExecution, UUID> {

    Optional<ApplicationExecution> findByIdAndUserId(UUID id, UUID userId);

    Optional<ApplicationExecution> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByExecutionStatus(String executionStatus);

    /** Phase 7.16.3 — the Automation Recovery Center's retry queue: due RETRY rows. */
    List<ApplicationExecution> findByExecutionStatusAndNextRetryAtBefore(String executionStatus, Instant before);

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
