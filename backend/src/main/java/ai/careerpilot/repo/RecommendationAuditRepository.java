package ai.careerpilot.repo;

import ai.careerpilot.domain.RecommendationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only scoring-breakdown audit trail for {@link RecommendationAudit}. Reads are
 * user-scoped by the caller; rows are never updated in normal operation.
 */
public interface RecommendationAuditRepository extends JpaRepository<RecommendationAudit, UUID> {

    List<RecommendationAudit> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
