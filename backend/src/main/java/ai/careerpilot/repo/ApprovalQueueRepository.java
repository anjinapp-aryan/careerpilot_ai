package ai.careerpilot.repo;

import ai.careerpilot.domain.ApprovalQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalQueueRepository extends JpaRepository<ApprovalQueueEntry, UUID> {

    Optional<ApprovalQueueEntry> findByIdAndUserId(UUID id, UUID userId);

    List<ApprovalQueueEntry> findByUserIdAndStatusOrderByRequestedAtDesc(UUID userId, String status);

    long countByStatus(String status);

    /**
     * P7 Action 2 — atomically claims a PENDING approval and moves it to a terminal decision
     * ({@code APPROVED} or {@code REJECTED}) in one conditional UPDATE, closing the same class of
     * defect Action 1 fixed for {@code claimForSubmit}: {@code ApprovalService}'s previous
     * read-PENDING-then-write was a check-then-act race — two concurrent {@code approve()} calls
     * (a double-click, a duplicate HTTP retry, or a concurrent {@code approve()}/{@code reject()}
     * pair on the same row) could both observe {@code PENDING} before either committed and both
     * transition the row, the second one publishing a second {@code ApprovalGrantedEvent}.
     *
     * <p>{@code @Transactional} here is load-bearing for the identical reason Action 1's live
     * Testcontainers/Postgres run established for {@code claimForSubmit}: Spring Data's repository
     * proxy does not synthesize a transaction for a bare {@code @Modifying} query method, so a call
     * with no ambient transaction throws {@code TransactionRequiredException} rather than silently
     * doing nothing. Declaring it here (not on the caller) keeps the transaction scoped to exactly
     * this one UPDATE.
     *
     * @return 1 when this caller won the claim, 0 when the row was no longer PENDING
     */
    @Transactional
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = """
            UPDATE approval_queue
            SET status = :status, decided_by = :decidedBy, decision_note = :note, decided_at = :decidedAt
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int claimDecision(@org.springframework.data.repository.query.Param("id") UUID id,
                       @org.springframework.data.repository.query.Param("status") String status,
                       @org.springframework.data.repository.query.Param("decidedBy") String decidedBy,
                       @org.springframework.data.repository.query.Param("note") String note,
                       @org.springframework.data.repository.query.Param("decidedAt") Instant decidedAt);
}
