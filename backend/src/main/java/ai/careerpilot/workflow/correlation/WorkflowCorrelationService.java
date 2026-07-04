package ai.careerpilot.workflow.correlation;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.0 — mints and advances the {@link WorkflowCorrelation} row that ties a whole Phase 3A
 * workflow instance together. Pure bookkeeping: it is invoked only by the (dark-by-default) workers,
 * so with stock flags it is never called. It NEVER throws — a correlation-tracking failure must never
 * break the workflow it is observing; {@link #start} still returns a usable id even if persistence
 * fails, so the event chain can proceed with correlation best-effort.
 */
@Service
public class WorkflowCorrelationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCorrelationService.class);
    public static final String STAGE_ENTRY = "ENTRY";

    private final WorkflowCorrelationRepository repo;
    private final WorkflowMetrics metrics;

    public WorkflowCorrelationService(WorkflowCorrelationRepository repo, WorkflowMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    /** Create a correlation row for a new workflow instance and return its correlation id. Never throws. */
    @Transactional
    public UUID start(String workflowType, UUID userId, UUID jobId, UUID applicationId) {
        UUID correlationId = UUID.randomUUID();
        metrics.recordCorrelationStart();
        try {
            repo.save(WorkflowCorrelation.builder()
                    .correlationId(correlationId)
                    .userId(userId).jobId(jobId).applicationId(applicationId)
                    .workflowType(workflowType)
                    .workflowStage(STAGE_ENTRY)
                    .status(WorkflowCorrelation.STATUS_STARTED)
                    .build());
        } catch (Exception e) {
            metrics.recordCorrelationFailure();
            log.warn("WORKFLOW_CORRELATION start failed type={} user={}: {}",
                    workflowType, userId, e.toString());
        }
        return correlationId;
    }

    /** Advance an existing correlation to a new stage/status. No-op if the id is unknown. Never throws. */
    @Transactional
    public void advance(UUID correlationId, String stage, String status) {
        if (correlationId == null) return;
        metrics.recordCorrelationAdvance();
        try {
            Optional<WorkflowCorrelation> found = repo.findByCorrelationId(correlationId);
            if (found.isEmpty()) return;
            WorkflowCorrelation c = found.get();
            c.setWorkflowStage(stage);
            c.setStatus(status);
            repo.save(c);
        } catch (Exception e) {
            metrics.recordCorrelationFailure();
            log.warn("WORKFLOW_CORRELATION advance failed id={} stage={}: {}",
                    correlationId, stage, e.toString());
        }
    }
}
