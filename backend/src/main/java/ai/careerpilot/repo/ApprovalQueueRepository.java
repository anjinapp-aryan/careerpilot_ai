package ai.careerpilot.repo;

import ai.careerpilot.domain.ApprovalQueueEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalQueueRepository extends JpaRepository<ApprovalQueueEntry, UUID> {

    Optional<ApprovalQueueEntry> findByIdAndUserId(UUID id, UUID userId);

    List<ApprovalQueueEntry> findByUserIdAndStatusOrderByRequestedAtDesc(UUID userId, String status);

    long countByStatus(String status);
}
