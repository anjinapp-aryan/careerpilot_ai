package ai.careerpilot.repo;

import ai.careerpilot.domain.RecommendationAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Append-only scoring-breakdown audit trail for {@link RecommendationAudit}. Reads are
 * user-scoped by the caller; rows are never updated in normal operation, except the Phase 2C-3
 * best-effort decision stamp (see {@code findFirstByUserIdAndJobIdOrderByCreatedAtDesc}).
 */
public interface RecommendationAuditRepository extends JpaRepository<RecommendationAudit, UUID> {

    List<RecommendationAudit> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Phase 2C-3: the latest scoring-audit row for a job, to stamp the user's decision onto (replay). */
    Optional<RecommendationAudit> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);
}
