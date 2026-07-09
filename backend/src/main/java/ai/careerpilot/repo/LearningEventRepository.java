package ai.careerpilot.repo;

import ai.careerpilot.domain.LearningEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LearningEventRepository extends JpaRepository<LearningEvent, UUID> {
    List<LearningEvent> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<LearningEvent> findByUserIdAndEventTypeOrderByCreatedAtDesc(UUID userId, String eventType);
    long countByEventType(String eventType);
}
