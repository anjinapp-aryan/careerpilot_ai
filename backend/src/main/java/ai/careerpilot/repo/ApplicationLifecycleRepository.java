package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationLifecycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationLifecycleRepository extends JpaRepository<ApplicationLifecycle, UUID> {

    Optional<ApplicationLifecycle> findByUserIdAndJobId(UUID userId, UUID jobId);

    List<ApplicationLifecycle> findByUserId(UUID userId);

    long countByCurrentStatus(String currentStatus);
}
