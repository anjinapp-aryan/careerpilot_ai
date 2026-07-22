package ai.careerpilot.repo;

import ai.careerpilot.domain.ExecutionScreenshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExecutionScreenshotRepository extends JpaRepository<ExecutionScreenshot, UUID> {

    Optional<ExecutionScreenshot> findByApprovalQueueEntryId(UUID approvalQueueEntryId);

    Optional<ExecutionScreenshot> findFirstByExecutionIdOrderByCapturedAtDesc(UUID executionId);

    /** Phase 7.16.4 — the Screenshot Timeline for the Application Detail Workspace's Evidence tab. */
    List<ExecutionScreenshot> findByExecutionIdOrderByCapturedAtAsc(UUID executionId);
}
