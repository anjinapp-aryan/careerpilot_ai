package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationSubmissionSessionRepository extends JpaRepository<ApplicationSubmissionSession, UUID> {

    Optional<ApplicationSubmissionSession> findByIdAndUserId(UUID id, UUID userId);

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
