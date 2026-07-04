package ai.careerpilot.workflow.correlation;

import ai.careerpilot.domain.WorkflowDeadLetter;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Phase 3A.0 — the failure sink for the workflow engine. Every worker's catch block routes here
 * instead of propagating, so one failing stage is isolated and captured for later human replay. This
 * service is the last line of defence, so it MUST NOT throw: its own persistence failure is swallowed
 * and only logged (there is nowhere safe left to escalate to).
 */
@Service
public class WorkflowDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDeadLetterService.class);
    private static final int MAX_PAYLOAD = 8000;
    private static final int MAX_EXCEPTION = 8000;

    private final WorkflowDeadLetterRepository repo;
    private final WorkflowMetrics metrics;

    public WorkflowDeadLetterService(WorkflowDeadLetterRepository repo, WorkflowMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
    }

    /** Capture a failed workflow stage. Never throws. */
    @Transactional
    public void record(UUID correlationId, String workflow, String stage, String payload, Throwable exception) {
        metrics.recordDeadLetter();
        try {
            repo.save(WorkflowDeadLetter.builder()
                    .correlationId(correlationId)
                    .workflow(workflow)
                    .stage(stage)
                    .payload(truncate(payload, MAX_PAYLOAD))
                    .exception(truncate(exception == null ? null : exception.toString(), MAX_EXCEPTION))
                    .retryCount(0)
                    .build());
            log.warn("WORKFLOW_DEAD_LETTER workflow={} stage={} correlation={}", workflow, stage, correlationId);
        } catch (Exception e) {
            metrics.recordDeadLetterFailure();
            log.error("WORKFLOW_DEAD_LETTER persist failed workflow={} stage={}: {}", workflow, stage, e.toString());
        }
    }

    public List<WorkflowDeadLetter> forCorrelation(UUID correlationId) {
        return repo.findByCorrelationIdOrderByCreatedAtDesc(correlationId);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
