package ai.careerpilot.repo;

import ai.careerpilot.domain.ExecutionScreenshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExecutionScreenshotRepository extends JpaRepository<ExecutionScreenshot, UUID> {

    Optional<ExecutionScreenshot> findByApprovalQueueEntryId(UUID approvalQueueEntryId);

    Optional<ExecutionScreenshot> findFirstByExecutionIdOrderByCapturedAtDesc(UUID executionId);
}
