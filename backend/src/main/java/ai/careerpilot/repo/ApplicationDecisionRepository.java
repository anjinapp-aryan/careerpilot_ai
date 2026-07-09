package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApplicationDecisionRepository extends JpaRepository<ApplicationDecision, UUID> {
    Optional<ApplicationDecision> findFirstByUserIdAndJobIdOrderByDecidedAtDesc(UUID userId, UUID jobId);
    List<ApplicationDecision> findByUserIdOrderByDecidedAtDesc(UUID userId);
    long countByOutcome(String outcome);
}
