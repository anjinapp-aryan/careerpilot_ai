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

    long countByStatus(String status);
}
