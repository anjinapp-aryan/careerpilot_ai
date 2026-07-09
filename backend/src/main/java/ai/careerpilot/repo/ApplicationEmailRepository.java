package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationEmail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationEmailRepository extends JpaRepository<ApplicationEmail, UUID> {

    List<ApplicationEmail> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByCategory(String category);
}
