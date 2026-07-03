package ai.careerpilot.repo;

import ai.careerpilot.domain.RecommendationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Append-only feedback log (Phase 2C-4). Reads are user-scoped by the caller. */
public interface RecommendationFeedbackRepository extends JpaRepository<RecommendationFeedback, UUID> {

    List<RecommendationFeedback> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
