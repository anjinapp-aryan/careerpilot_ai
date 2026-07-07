package ai.careerpilot.repo;

import ai.careerpilot.domain.LearningMetricsLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LearningMetricsLogRepository extends JpaRepository<LearningMetricsLog, UUID> {
    List<LearningMetricsLog> findTop50ByStageOrderByCreatedAtDesc(String stage);
    long countByStageAndStatus(String stage, String status);
}
