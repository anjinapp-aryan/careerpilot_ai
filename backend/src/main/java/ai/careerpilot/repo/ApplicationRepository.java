package ai.careerpilot.repo;

import ai.careerpilot.domain.Application;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {
    List<Application> findByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserIdAndStatus(UUID userId, String status);
}
