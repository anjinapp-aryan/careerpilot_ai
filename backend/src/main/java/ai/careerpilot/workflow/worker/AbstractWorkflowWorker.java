package ai.careerpilot.workflow.worker;

import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.BaseWorkflowEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Phase 3A — shared base for the nine workflow-stage workers. Centralises the two failure-isolation
 * boundaries every worker needs so no stage can ever break the workflow or the triggering transaction:
 *
 * <ol>
 *   <li>the {@code executor.execute(...)} submit itself (a rejected task from a full bounded queue),
 *       and</li>
 *   <li>the stage body running on the bounded executor thread.</li>
 * </ol>
 *
 * Both are wrapped: any {@link Throwable} is captured as a {@code workflow_dead_letter} row via
 * {@link WorkflowDeadLetterService} and never propagates. Concrete workers keep their own
 * {@code @Async @TransactionalEventListener(AFTER_COMMIT)} method plus the mandatory
 * {@code if (!triggerEnabled || !service.isEnabled()) return;} guard, then delegate the body here.
 */
public abstract class AbstractWorkflowWorker {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    private final ThreadPoolTaskExecutor executor;
    private final WorkflowDeadLetterService deadLetter;
    private final String workflow;

    protected AbstractWorkflowWorker(ThreadPoolTaskExecutor executor,
                                     WorkflowDeadLetterService deadLetter, String workflow) {
        this.executor = executor;
        this.deadLetter = deadLetter;
        this.workflow = workflow;
    }

    /** Dispatch a stage body onto the dedicated bounded executor with dead-letter isolation on both hops. */
    protected void dispatch(BaseWorkflowEvent event, String stage, Runnable body) {
        try {
            executor.execute(() -> {
                try {
                    body.run();
                } catch (Throwable t) {
                    deadLetter.record(event.correlationId(), workflow, stage, payload(event), t);
                }
            });
        } catch (Throwable t) {
            // e.g. TaskRejectedException from a saturated queue (AbortPolicy) — isolate, never propagate.
            deadLetter.record(event.correlationId(), workflow, stage, payload(event), t);
        }
    }

    private static String payload(BaseWorkflowEvent event) {
        return event == null ? null : event.toString();
    }
}
