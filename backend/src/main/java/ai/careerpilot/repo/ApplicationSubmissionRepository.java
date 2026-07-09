package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationSubmission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationSubmissionRepository extends JpaRepository<ApplicationSubmission, UUID> {
    Optional<ApplicationSubmission> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);
    List<ApplicationSubmission> findByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByStatus(String status);
}
