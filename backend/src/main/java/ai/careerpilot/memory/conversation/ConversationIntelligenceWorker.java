package ai.careerpilot.memory.conversation;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.memory.conversation.ConversationDecisionExtractor.ExtractedDecision;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import ai.careerpilot.service.copilot.event.CopilotUserMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.15.2 — the pipeline: Conversation -> Intent/Decision Detection (delegated entirely to
 * {@link ConversationDecisionExtractor}) -> Confidence threshold -> Duplicate validation ->
 * {@link CareerMemoryService#record} -> (already existing) {@code CareerContextRetriever}
 * exposes it. No new storage — every accepted decision becomes an ordinary
 * {@link CareerDecisionMemory} row, indistinguishable at read time from one extracted by
 * {@code CareerMemoryEventBridge} or {@code RecommendationFeedbackService}; the existing
 * {@code CareerMemoryBooster} recommendation integration and Copilot retrieval need zero changes
 * to pick these up.
 *
 * <p>Published on a plain {@link EventListener}, not {@code @TransactionalEventListener}: the
 * event is published from {@code CopilotService.streamTurn} outside any active transaction
 * (the message it describes was already committed by {@code ConversationMemory.append}'s own
 * transaction by that point), so there is nothing to defer to — {@code @Async} alone is what
 * keeps this off the request thread.
 */
@Component
public class ConversationIntelligenceWorker {

    private static final Logger log = LoggerFactory.getLogger(ConversationIntelligenceWorker.class);

    private final ConversationDecisionExtractor extractor;
    private final CareerMemoryService careerMemory;
    private final CareerDecisionMemoryRepository memories;
    private final ConversationIntelligenceMetrics metrics;
    private final boolean enabled;
    private final double minConfidence;
    private final int dedupWindowHours;
    private final int temporaryTtlDays;

    public ConversationIntelligenceWorker(ConversationDecisionExtractor extractor, CareerMemoryService careerMemory,
                                          CareerDecisionMemoryRepository memories, ConversationIntelligenceMetrics metrics,
                                          @Value("${career.memory.conversation.enabled:false}") boolean enabled,
                                          @Value("${career.memory.conversation.min-confidence:0.7}") double minConfidence,
                                          @Value("${career.memory.conversation.dedup-window-hours:24}") int dedupWindowHours,
                                          @Value("${career.memory.conversation.temporary-ttl-days:90}") int temporaryTtlDays) {
        this.extractor = extractor;
        this.careerMemory = careerMemory;
        this.memories = memories;
        this.metrics = metrics;
        this.enabled = enabled;
        this.minConfidence = minConfidence;
        this.dedupWindowHours = dedupWindowHours;
        this.temporaryTtlDays = temporaryTtlDays;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Async(ConversationIntelligenceExecutorConfig.CONVERSATION_INTELLIGENCE_EXECUTOR)
    @EventListener
    public void onUserMessage(CopilotUserMessageEvent event) {
        if (!enabled) return;
        long start = System.nanoTime();
        metrics.recordConversationAnalyzed();
        try {
            List<ExtractedDecision> decisions = extractor.extract(event.message());
            for (ExtractedDecision d : decisions) {
                metrics.recordDecisionDetected(d.confidence().doubleValue());
                if (d.confidence().doubleValue() < minConfidence) {
                    metrics.recordRejectedLowConfidence();
                    continue;
                }
                if (isDuplicate(event.userId(), d)) {
                    metrics.recordDuplicateSkipped();
                    continue;
                }
                writeMemory(event, d);
                metrics.recordAccepted();
            }
        } catch (Exception e) {
            log.warn("CONVERSATION_INTEL analysis failed conversation={}: {}", event.conversationId(), e.toString());
        } finally {
            metrics.recordProcessingLatency(System.nanoTime() - start);
        }
    }

    /**
     * "Merge similar decisions... never overwrite... newest preference should have higher
     * priority... maintain complete history": a genuinely new value (Germany -> Canada) is
     * NEVER a duplicate — it's written as a new row, and {@code CareerMemoryService}'s existing
     * freshness-weighted ranking is what makes it naturally outrank the older one at retrieval
     * time. Only an exact restatement of the same value within the dedup window is skipped, so
     * a user re-explaining the same preference across several turns doesn't spam the ledger.
     */
    private boolean isDuplicate(UUID userId, ExtractedDecision d) {
        Optional<CareerDecisionMemory> mostRecent = memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, d.category());
        if (mostRecent.isEmpty()) return false;
        CareerDecisionMemory prior = mostRecent.get();
        boolean sameValue = prior.getValue() != null && prior.getValue().equalsIgnoreCase(d.value());
        boolean withinWindow = prior.getCreatedAt() != null
                && prior.getCreatedAt().isAfter(Instant.now().minus(Duration.ofHours(dedupWindowHours)));
        return sameValue && withinWindow;
    }

    private void writeMemory(CopilotUserMessageEvent event, ExtractedDecision d) {
        String decisionType = d.category() + "_" + d.polarity();
        int importance = "PERMANENT".equals(d.permanence()) ? 5 : 3;
        Instant expiresAt = "TEMPORARY".equals(d.permanence())
                ? Instant.now().plus(Duration.ofDays(temporaryTtlDays)) : null;
        careerMemory.record(event.userId(), decisionType, d.category(), d.value(), d.reason(),
                d.confidence(), "COPILOT_CONVERSATION", importance, true,
                null, null, event.conversationId() == null ? null : event.conversationId().toString(), expiresAt);
    }
}
