package ai.careerpilot.story.worker;

import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.learning.event.LearningEventRecordedEvent;
import ai.careerpilot.story.StarStoryEngine;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.config.StoryExecutorConfig;
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
 * Phase 7.15 — a NEW consumer on the EXISTING Phase 6.1 {@link LearningEventRecordedEvent} (no
 * learning code changes, no duplicated learning), same reuse pattern as
 * {@code CompanyKnowledgeWorker}. On a real outcome signal (application submitted / interview
 * completed / offer received), auto-drafts a candidate STAR story so the library builds itself
 * from real platform activity instead of requiring the user to write everything from scratch.
 *
 * <p>Dark by default: gated by BOTH {@code story.worker.trigger.enabled} and the engine's own
 * {@code story.engine.enabled} (+ generation flag, checked inside the engine). Runs on the
 * dedicated bounded executor; failures are logged and isolated — story drafting must never break
 * the learning pipeline.
 */
@Component
public class StoryExtractionWorker {

    private static final Logger log = LoggerFactory.getLogger(StoryExtractionWorker.class);

    private final StarStoryEngine engine;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public StoryExtractionWorker(StarStoryEngine engine,
                                 @Qualifier(StoryExecutorConfig.STORY_EXECUTOR) ThreadPoolTaskExecutor executor,
                                 @Value("${story.worker.trigger.enabled:false}") boolean triggerEnabled) {
        this.engine = engine;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    public boolean isEnabled() { return triggerEnabled && engine.isEnabled(); }

    @Async(StoryExecutorConfig.STORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onLearningEventRecorded(LearningEventRecordedEvent event) {
        if (!triggerEnabled || !engine.isEnabled()) return;
        try {
            executor.execute(() -> draft(event));
        } catch (Exception e) {
            log.warn("STORY_INTEL worker dispatch failed event={}: {}", event.learningEventId(), e.toString());
        }
    }

    void draft(LearningEventRecordedEvent event) {
        try {
            StoryType type = mapEventType(event.eventType());
            if (type == null) return;
            engine.generate(event.userId(), type, null,
                    "Auto-drafted from a real " + event.eventType() + " outcome; refine before use.");
        } catch (Exception e) {
            log.warn("STORY_INTEL draft failed event={}: {}", event.learningEventId(), e.toString());
        }
    }

    /** Learning outcome → the most fitting story type to auto-draft, or null for signals with no story angle. */
    static StoryType mapEventType(LearningEventType type) {
        if (type == null) return null;
        return switch (type) {
            case APPLICATION_SUBMITTED -> StoryType.DELIVERY;
            case INTERVIEW_COMPLETED -> StoryType.COMMUNICATION;
            case OFFER_RECEIVED, OFFER_ACCEPTED, APPLICATION_ACCEPTED -> StoryType.SUCCESS;
            case RECOMMENDATION_APPROVED -> StoryType.CAREER_GROWTH;
            default -> null;
        };
    }
}
