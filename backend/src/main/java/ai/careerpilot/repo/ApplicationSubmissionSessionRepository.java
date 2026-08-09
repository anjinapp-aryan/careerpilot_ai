package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationSubmissionSessionRepository extends JpaRepository<ApplicationSubmissionSession, UUID> {

    Optional<ApplicationSubmissionSession> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Guided Apply — the same atomic conditional-UPDATE pattern Action 2 established for
     * {@code ApprovalQueueRepository.claimDecision}: a plain read-then-write (read status in Java,
     * check, then save) has a real check-then-act race between two concurrent "I submitted it"
     * requests (e.g. a double-tap, or two open tabs). Only one caller's UPDATE can match {@code
     * status = 'WAITING_MANUAL_SUBMISSION'} — the loser's affected-row-count is 0, which the service
     * layer turns into a 409 instead of silently overwriting the winner's note/timestamp.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE application_submission_session
            SET status = :status, user_reported_submitted_at = :reportedAt, user_submission_note = :note,
                completed_at = :reportedAt
            WHERE id = :id AND user_id = :userId AND status = 'WAITING_MANUAL_SUBMISSION'
            """, nativeQuery = true)
    int claimUserReportedSubmitted(@Param("id") UUID id, @Param("userId") UUID userId,
                                   @Param("status") String status, @Param("reportedAt") Instant reportedAt,
                                   @Param("note") String note);

    List<ApplicationSubmissionSession> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<ApplicationSubmissionSession> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    List<ApplicationSubmissionSession> findByStatusInOrderByCreatedAtDesc(List<String> statuses);

    Optional<ApplicationSubmissionSession> findByApprovalQueueEntryId(UUID approvalQueueEntryId);

    /**
     * Sessions in one of {@code statuses} whose last write is older than {@code before} — the work
     * list for the stranded-session reaper.
     *
     * <p>A submission session is driven by a single in-memory thread on the bounded submission
     * executor. If that thread dies (JVM killed mid-pipeline, dispatch rejected, uncaught Error),
     * nothing else owns the row: the status column has no next writer and the session sits in an
     * intermediate state forever. This finder is how such rows are found again.
     *
     * <p>Bounded by construction — the caller passes only non-terminal, non-parked statuses, and
     * the {@code (status)} index already exists.
     */
    List<ApplicationSubmissionSession> findByStatusInAndUpdatedAtBefore(List<String> statuses,
                                                                        java.time.Instant before);

    long countByStatus(String status);
}
