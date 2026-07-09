package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationTracking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationTrackingRepository extends JpaRepository<ApplicationTracking, UUID> {

    List<ApplicationTracking> findByUserIdAndJobIdOrderByChangedAtDesc(UUID userId, UUID jobId);

    long countByStatus(String status);
}
