package ai.careerpilot.learning;

import ai.careerpilot.domain.LearningMetricsLog;
import ai.careerpilot.repo.LearningMetricsLogRepository;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 6.1 — the single entry point {@code LearningEventBridge}'s listeners call. Gated by the
 * master {@code learning.engine.enabled} flag; isolates every capture failure to the shared
 * {@code WorkflowDeadLetterService} sink (reused, not duplicated) so a learning-engine failure can
 * never affect the existing engine whose event it observed. Never throws.
 */
@Service
public class LearningPipeline {

    private static final Logger log = LoggerFactory.getLogger(LearningPipeline.class);
    private static final String WORKFLOW_NAME = "LEARNING";

    private final LearningEventService eventService;
    private final LearningMetricsLogRepository metricsLog;
    private final WorkflowDeadLetterService deadLetters;
    private final boolean enabled;

    public LearningPipeline(LearningEventService eventService,
                            LearningMetricsLogRepository metricsLog,
                            WorkflowDeadLetterService deadLetters,
                            @Value("${learning.engine.enabled:false}") boolean enabled) {
        this.eventService = eventService;
        this.metricsLog = metricsLog;
        this.deadLetters = deadLetters;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void capture(LearningEventType type, UUID correlationId, UUID userId, UUID jobId,
                        String resumeVersion, String workflowId) {
        if (!enabled || userId == null) return;
        try {
            eventService.record(type, correlationId, userId, jobId, resumeVersion, workflowId);
        } catch (Exception e) {
            log.warn("Learning event capture failed type={} user={}: {}", type, userId, e.toString());
            try {
                metricsLog.save(LearningMetricsLog.builder()
                        .stage(LearningMetricsLog.STAGE_EVENT_CAPTURE)
                        .status(LearningMetricsLog.STATUS_FAILED)
                        .errorMessage(e.toString())
                        .build());
            } catch (Exception ignore) {
                // best-effort audit; never escalate
            }
            deadLetters.record(correlationId, WORKFLOW_NAME, "EVENT_CAPTURE", "type=" + type + " userId=" + userId, e);
        }
    }
}
