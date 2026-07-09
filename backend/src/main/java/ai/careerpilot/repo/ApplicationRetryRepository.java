package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationRetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationRetryRepository extends JpaRepository<ApplicationRetry, UUID> {

    List<ApplicationRetry> findByApplicationExecutionIdOrderByAttemptAsc(UUID applicationExecutionId);

    long countByApplicationExecutionId(UUID applicationExecutionId);
}
