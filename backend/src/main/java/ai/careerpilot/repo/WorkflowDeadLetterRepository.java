package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowDeadLetterRepository extends JpaRepository<WorkflowDeadLetter, UUID> {

    List<WorkflowDeadLetter> findByCorrelationIdOrderByCreatedAtDesc(UUID correlationId);

    long countByWorkflow(String workflow);
}
