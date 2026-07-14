package ai.careerpilot.repo;

import ai.careerpilot.domain.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    List<Application> findByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserIdAndStatus(UUID userId, String status);

    /** Phase 2C-3: the user's most recent application row for a job, if any (decision upsert target). */
    java.util.Optional<Application> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /** Application Command Center: ownership-scoped bulk fetch for the bulk-action endpoint. */
    List<Application> findByUserIdAndIdIn(UUID userId, List<UUID> ids);
}
