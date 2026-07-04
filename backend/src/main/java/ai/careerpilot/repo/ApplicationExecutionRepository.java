package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ApplicationExecutionRepository extends JpaRepository<ApplicationExecution, UUID> {

    Optional<ApplicationExecution> findByIdAndUserId(UUID id, UUID userId);

    Optional<ApplicationExecution> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByExecutionStatus(String executionStatus);
}
