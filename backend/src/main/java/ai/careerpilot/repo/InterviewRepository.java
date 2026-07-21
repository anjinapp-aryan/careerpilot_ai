package ai.careerpilot.repo;

import ai.careerpilot.domain.Interview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InterviewRepository extends JpaRepository<Interview, UUID> {

    List<Interview> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /** Phase 7.15.3A — every interview round for a user, newest first, for the dashboard's Interview Insights section. */
    List<Interview> findByUserIdOrderByCreatedAtDesc(UUID userId);

    long countByUserId(UUID userId);
}
