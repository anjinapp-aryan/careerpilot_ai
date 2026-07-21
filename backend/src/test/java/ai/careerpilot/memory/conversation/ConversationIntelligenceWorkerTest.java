package ai.careerpilot.memory.conversation;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.memory.conversation.ConversationDecisionExtractor.ExtractedDecision;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import ai.careerpilot.service.copilot.event.CopilotUserMessageEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 7.15.2 — disabled must be a total no-op (never even calls the extractor); below-threshold
 * decisions must never reach {@code CareerMemoryService.record}; an exact-value restatement
 * within the dedup window must be skipped, but a genuinely different value must always be
 * written (never overwrite history — new preference, new row); and nothing here may ever
 * propagate an exception, since this runs off an async event listener with no caller to catch it.
 */
class ConversationIntelligenceWorkerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID conversationId = UUID.randomUUID();

    private ConversationIntelligenceWorker worker(ConversationDecisionExtractor extractor,
                                                   CareerMemoryService careerMemory,
                                                   CareerDecisionMemoryRepository memories,
                                                   boolean enabled) {
        return new ConversationIntelligenceWorker(extractor, careerMemory, memories,
                new ConversationIntelligenceMetrics(), enabled, 0.7, 24, 90);
    }

    private CopilotUserMessageEvent event(String message) {
        return new CopilotUserMessageEvent(conversationId, userId, message, "jobs", null);
    }

    @Test
    void disabledNeverCallsExtractor() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        worker(extractor, careerMemory, mock(CareerDecisionMemoryRepository.class), false)
                .onUserMessage(event("I want Germany only"));
        verifyNoInteractions(extractor, careerMemory);
    }

    @Test
    void belowThresholdDecisionIsNeverWritten() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("COUNTRY", "Germany", "POSITIVE", "PERMANENT", BigDecimal.valueOf(0.5)))); // below 0.7

        worker(extractor, careerMemory, memories, true).onUserMessage(event("maybe Germany?"));

        verify(careerMemory, never()).record(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void aboveThresholdNewDecisionIsWritten() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("COUNTRY", "Germany", "POSITIVE", "PERMANENT", BigDecimal.valueOf(0.95))));
        when(memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, "COUNTRY")).thenReturn(Optional.empty());

        worker(extractor, careerMemory, memories, true).onUserMessage(event("I want Germany only"));

        verify(careerMemory).record(eq(userId), eq("COUNTRY_POSITIVE"), eq("COUNTRY"), eq("Germany"), any(),
                eq(BigDecimal.valueOf(0.95)), eq("COPILOT_CONVERSATION"), eq(5), eq(true), isNull(), isNull(),
                eq(conversationId.toString()), isNull());
    }

    @Test
    void exactRestatementWithinWindowIsSkippedAsDuplicate() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("COUNTRY", "Germany", "POSITIVE", "PERMANENT", BigDecimal.valueOf(0.9))));
        when(memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, "COUNTRY")).thenReturn(
                Optional.of(existingMemory("Germany", Instant.now().minus(1, ChronoUnit.HOURS))));

        worker(extractor, careerMemory, memories, true).onUserMessage(event("I want Germany only, seriously"));

        verify(careerMemory, never()).record(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(), any(), any(), any(), any());
    }

    @Test
    void differentValueIsNeverTreatedAsDuplicateEvenWithinWindow() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("COUNTRY", "Canada", "POSITIVE", "PERMANENT", BigDecimal.valueOf(0.9))));
        when(memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, "COUNTRY")).thenReturn(
                Optional.of(existingMemory("Germany", Instant.now().minus(1, ChronoUnit.HOURS))));

        worker(extractor, careerMemory, memories, true).onUserMessage(event("actually I'm open to Canada too"));

        // Germany is never deleted or overwritten — this just verifies the NEW Canada preference
        // is written as its own row, exactly as required ("store both, never delete history").
        verify(careerMemory).record(eq(userId), eq("COUNTRY_POSITIVE"), eq("COUNTRY"), eq("Canada"), any(),
                any(), eq("COPILOT_CONVERSATION"), anyInt(), eq(true), isNull(), isNull(), any(), isNull());
    }

    @Test
    void sameValueOutsideDedupWindowIsWrittenAgain() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("COUNTRY", "Germany", "POSITIVE", "PERMANENT", BigDecimal.valueOf(0.9))));
        when(memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(userId, "COUNTRY")).thenReturn(
                Optional.of(existingMemory("Germany", Instant.now().minus(48, ChronoUnit.HOURS)))); // outside 24h window

        worker(extractor, careerMemory, memories, true).onUserMessage(event("I still want Germany only"));

        verify(careerMemory).record(eq(userId), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(),
                any(), any(), any(), any());
    }

    @Test
    void temporaryDecisionsGetAnExpiryPermanentOnesDoNot() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        CareerDecisionMemoryRepository memories = mock(CareerDecisionMemoryRepository.class);
        when(extractor.extract(any())).thenReturn(List.of(
                decision("LEARNING", "Kubernetes", "POSITIVE", "TEMPORARY", BigDecimal.valueOf(0.9))));
        when(memories.findFirstByUserIdAndCategoryOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());

        worker(extractor, careerMemory, memories, true).onUserMessage(event("I'm learning Kubernetes"));

        verify(careerMemory).record(any(), any(), any(), any(), any(), any(), any(), anyInt(), anyBoolean(),
                any(), any(), any(), argThat(java.util.Objects::nonNull));
    }

    @Test
    void extractorExceptionNeverPropagates() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        when(extractor.extract(any())).thenThrow(new RuntimeException("provider down"));
        ConversationIntelligenceWorker worker = worker(extractor, mock(CareerMemoryService.class),
                mock(CareerDecisionMemoryRepository.class), true);
        assertDoesNotThrow(() -> worker.onUserMessage(event("I want Germany only")));
    }

    @Test
    void casualChatProducesNoDecisionsAndNoWrite() {
        ConversationDecisionExtractor extractor = mock(ConversationDecisionExtractor.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        when(extractor.extract(any())).thenReturn(List.of());

        worker(extractor, careerMemory, mock(CareerDecisionMemoryRepository.class), true)
                .onUserMessage(event("Hello, good morning!"));

        verifyNoInteractions(careerMemory);
    }

    private ExtractedDecision decision(String category, String value, String polarity, String permanence, BigDecimal confidence) {
        return new ExtractedDecision(category, value, polarity, permanence, "because reasons", "source sentence", confidence);
    }

    private CareerDecisionMemory existingMemory(String value, Instant createdAt) {
        return CareerDecisionMemory.builder().id(UUID.randomUUID()).userId(userId)
                .decisionType("COUNTRY_POSITIVE").category("COUNTRY").value(value).createdAt(createdAt).build();
    }
}
