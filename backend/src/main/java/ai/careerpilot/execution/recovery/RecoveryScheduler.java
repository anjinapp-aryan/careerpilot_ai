package ai.careerpilot.execution.recovery;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Phase 7.16.3 — the Recovery Center's "Retry Queue" poller. Reuses the existing {@code
 * application_execution} table (filtered by {@code STATUS_RETRY} + due {@code nextRetryAt}) rather
 * than standing up a separate queue/scheduler; {@code @EnableScheduling} is already active on
 * {@code CareerPilotApplication} (see {@code JobDiscoveryScheduler} et al.). Ships dark via {@code
 * application.recovery.trigger.enabled=false} — with the flag off this method is a no-op poll.
 */
@Component
public class RecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecoveryScheduler.class);

    private final ApplicationExecutionRepository executions;
    private final ApplicationExecutionService executionService;
    private final boolean triggerEnabled;

    public RecoveryScheduler(ApplicationExecutionRepository executions,
                             ApplicationExecutionService executionService,
                             @Value("${application.recovery.trigger.enabled:false}") boolean triggerEnabled) {
        this.executions = executions;
        this.executionService = executionService;
        this.triggerEnabled = triggerEnabled;
    }

    @Scheduled(fixedDelayString = "${application.recovery.poll-interval-ms:30000}")
    public void pollDueRetries() {
        if (!triggerEnabled) return;
        List<ApplicationExecution> due;
        try {
            due = executions.findByExecutionStatusAndNextRetryAtBefore(
                    ApplicationExecution.STATUS_RETRY, Instant.now());
        } catch (Exception e) {
            log.warn("RECOVERY_SCHEDULER poll query failed: {}", e.toString());
            return;
        }
        for (ApplicationExecution exec : due) {
            try {
                executionService.retryExecution(exec.getId());
            } catch (Exception e) {
                log.warn("RECOVERY_SCHEDULER retry failed execution={}: {}", exec.getId(), e.toString());
            }
        }
    }
}
