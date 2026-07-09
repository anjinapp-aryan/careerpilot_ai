package ai.careerpilot.repo;

import ai.careerpilot.domain.Interview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    List<Interview> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    long countByUserId(UUID userId);
}
