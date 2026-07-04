package ai.careerpilot.resumetailoring.coverletter;

import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.event.AtsExplainabilityCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2D.5 — reacts to {@link AtsExplainabilityCompletedEvent} and generates the cover letter
 * on the dedicated bounded {@code coverLetterExecutor} (the only pipeline stage after 2D.2 that
 * holds an LLM call, hence its own smaller queue). Async, after-commit, failure-isolated; gated
 * by {@code cover.letter.trigger.enabled}, independent of {@code cover.letter.enabled}.
 */
@Component
public class CoverLetterWorker {

    private static final Logger log = LoggerFactory.getLogger(CoverLetterWorker.class);

    private final CoverLetterService coverLetter;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public CoverLetterWorker(CoverLetterService coverLetter,
                             @Qualifier(PipelineExecutorsConfig.COVER_LETTER_EXECUTOR) ThreadPoolTaskExecutor executor,
                             @Value("${cover.letter.trigger.enabled:false}") boolean triggerEnabled) {
        this.coverLetter = coverLetter;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAtsExplainabilityCompleted(AtsExplainabilityCompletedEvent event) {
        if (!triggerEnabled || !coverLetter.isEnabled()) return;
        try {
            executor.execute(() -> coverLetter.generate(event.userId(), event.jobId(), event.resumeTailoringId()));
        } catch (Exception e) {
            log.warn("Cover letter worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
