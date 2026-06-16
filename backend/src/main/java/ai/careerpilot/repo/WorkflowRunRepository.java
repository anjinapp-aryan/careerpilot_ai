package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {
    Optional<WorkflowRun> findByThreadId(String threadId);
    List<WorkflowRun> findTop20ByUserIdOrderByCreatedAtDesc(UUID userId);
}
