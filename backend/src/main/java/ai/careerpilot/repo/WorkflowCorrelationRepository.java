package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowCorrelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WorkflowCorrelationRepository extends JpaRepository<WorkflowCorrelation, UUID> {

    Optional<WorkflowCorrelation> findByCorrelationId(UUID correlationId);

    long countByStatus(String status);
}
