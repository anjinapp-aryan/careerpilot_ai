package ai.careerpilot.workflow.entry;

import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.event.ApplicationCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Phase 3A — the DARK-by-default entry point of the whole application-tracking workflow. It is the
 * <em>only</em> place a {@link ApplicationCreatedEvent} (and therefore a correlation id) is minted, so
 * the entire 9-worker chain hangs off this one gate.
 *
 * <p>Two ways in, both gated by {@code workflow.tracking.trigger.enabled} (default {@code false}):
 * <ol>
 *   <li>a <em>second</em> listener on Phase 2E's {@link ApplicationSubmittedEvent} — 2E is left
 *       byte-for-byte untouched; nothing in the 2E build ever emits that event, so this is inert until
 *       both 2E execution and this flag are enabled; and</li>
 *   <li>{@link #seed} — a manual entry used by the {@code WorkflowController} seed endpoint to start a
 *       workflow for a job the human is tracking by hand.</li>
 * </ol>
 *
 * With stock flags this fires nothing: creating an application produces zero Phase 3A rows.
 */
@Component
public class WorkflowEntryBridge {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEntryBridge.class);
    static final String WORKFLOW_TYPE = "application-tracking";

    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public WorkflowEntryBridge(WorkflowCorrelationService correlation,
                               ApplicationEventPublisher events,
                               @Value("${workflow.tracking.trigger.enabled:false}") boolean triggerEnabled) {
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    public boolean isTriggerEnabled() {
        return triggerEnabled;
    }

    /**
     * Second listener on 2E's submission event. Dark unless {@code workflow.tracking.trigger.enabled}.
     * After-commit + fallbackExecution so it behaves identically whether or not a transaction is active.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        if (!triggerEnabled) return;
        try {
            open(event.userId(), event.jobId(), event.applicationId(), null, null, event.provider());
        } catch (Exception e) {
            // Entry must never break the triggering flow.
            log.warn("WORKFLOW_ENTRY bridge from ApplicationSubmittedEvent failed job={}: {}",
                    event.jobId(), e.toString());
        }
    }

    /**
     * Manual entry point (human seed). Returns the minted correlation id, or {@code null} when the gate
     * is off so the caller can report NOT_ENABLED. Never throws.
     */
    public UUID seed(UUID userId, UUID jobId, UUID applicationId, String company, String country, String source) {
        if (!triggerEnabled) return null;
        try {
            return open(userId, jobId, applicationId, company, country, source);
        } catch (Exception e) {
            log.warn("WORKFLOW_ENTRY seed failed user={} job={}: {}", userId, jobId, e.toString());
            return null;
        }
    }

    private UUID open(UUID userId, UUID jobId, UUID applicationId, String company, String country, String source) {
        UUID correlationId = correlation.start(WORKFLOW_TYPE, userId, jobId, applicationId);
        events.publishEvent(ApplicationCreatedEvent.open(
                correlationId, userId, jobId, applicationId, company, country, source));
        log.info("WORKFLOW_ENTRY opened correlation={} user={} job={}", correlationId, userId, jobId);
        return correlationId;
    }
}
