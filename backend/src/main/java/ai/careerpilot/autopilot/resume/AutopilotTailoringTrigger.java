package ai.careerpilot.autopilot.resume;

import ai.careerpilot.autopilot.resume.ResumeSelectionEngine.ResumeSelection;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Phase 7.3 — connects the agent's resume decision to the <em>existing</em> Phase 2D tailoring
 * pipeline instead of duplicating it. When {@link ResumeSelectionEngine} reports
 * {@code NEEDS_TAILORING}, this publishes the same {@link RecommendationApprovedEvent} the human
 * approve-flow uses, so the already-live chain runs unchanged:
 *
 * <pre>
 * RecommendationApprovedEvent -&gt; ResumeTailoringWorker -&gt; ResumeTailoringService.tailor()
 *   (new version, never overwrites; Phase 6.5 learning ordering + validation already inside)
 *   -&gt; ResumeTailoredEvent -&gt; AtsOptimizationWorker -&gt; ... -&gt; ready to apply
 * </pre>
 *
 * <p>No new worker, executor, or table — pure glue. Gated by {@code resume.autopilot.enabled}
 * (default off); the downstream pipeline stays independently gated by
 * {@code resume.tailoring.trigger-on-approve.enabled} + {@code resume.tailoring.enabled}, so with
 * stock flags this fires an event that lands on a disabled worker and nothing happens. Never throws.
 */
@Service
public class AutopilotTailoringTrigger {

    private static final Logger log = LoggerFactory.getLogger(AutopilotTailoringTrigger.class);

    private final ResumeSelectionEngine selectionEngine;
    private final ApplicationEventPublisher publisher;
    private final boolean enabled;

    public AutopilotTailoringTrigger(ResumeSelectionEngine selectionEngine,
                                     ApplicationEventPublisher publisher,
                                     @Value("${resume.autopilot.enabled:false}") boolean enabled) {
        this.selectionEngine = selectionEngine;
        this.publisher = publisher;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Ensure a suitable tailored resume exists for (user, job), triggering the existing pipeline if
     * not. {@code orgId} is carried through to the event for multi-tenant context. Never throws.
     */
    public TailoringTriggerOutcome triggerIfNeeded(UUID userId, UUID orgId, UUID jobId) {
        if (!enabled) return TailoringTriggerOutcome.NOT_TRIGGERED;
        try {
            ResumeSelection selection = selectionEngine.select(userId, jobId).orElse(null);
            if (selection == null) return TailoringTriggerOutcome.NOT_TRIGGERED; // selection engine disabled
            return switch (selection.outcome()) {
                case SELECTED -> TailoringTriggerOutcome.ALREADY_READY;
                case NO_BASE_RESUME -> TailoringTriggerOutcome.NO_BASE_RESUME;
                case NEEDS_TAILORING -> {
                    publisher.publishEvent(new RecommendationApprovedEvent(userId, orgId, jobId, null, null));
                    log.info("AUTOPILOT_TAILORING triggered pipeline user={} job={}", userId, jobId);
                    yield TailoringTriggerOutcome.TAILORING_TRIGGERED;
                }
            };
        } catch (Exception e) {
            log.warn("AUTOPILOT_TAILORING error user={} job={}: {}", userId, jobId, e.toString());
            return TailoringTriggerOutcome.NOT_TRIGGERED;
        }
    }
}
