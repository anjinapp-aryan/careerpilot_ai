package ai.careerpilot.memory.conversation;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.memory.conversation.ConversationDecisionExtractor.ExtractedDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 7.15.2 — parsing must be defensive: malformed/prose/empty model output never throws,
 * unknown categories are dropped rather than corrupting the ledger, and confidence is always
 * clamped to [0,1]. These are tested against {@code parse()} directly (no AI call) — same split
 * as {@code CandidateProfileExtractorTest} would use for {@code parseAndValidate}.
 */
class ConversationDecisionExtractorTest {

    private ConversationDecisionExtractor extractor() {
        return new ConversationDecisionExtractor(mock(AiGatewayService.class), 2000);
    }

    @Test
    void emptyArrayForCasualChat() {
        assertTrue(extractor().parse("[]").isEmpty());
    }

    @Test
    void parsesFencedJsonArray() {
        String raw = "```json\n[{\"category\":\"country\",\"value\":\"Germany\",\"polarity\":\"positive\","
                + "\"permanence\":\"permanent\",\"reason\":\"family\",\"sourceSentence\":\"I want Germany only\","
                + "\"confidence\":0.95}]\n```";
        List<ExtractedDecision> out = extractor().parse(raw);
        assertEquals(1, out.size());
        ExtractedDecision d = out.get(0);
        assertEquals("COUNTRY", d.category());
        assertEquals("Germany", d.value());
        assertEquals("POSITIVE", d.polarity());
        assertEquals("PERMANENT", d.permanence());
        assertEquals(0, BigDecimal.valueOf(0.95).compareTo(d.confidence()));
    }

    @Test
    void malformedJsonNeverThrowsAndReturnsEmpty() {
        assertDoesNotThrow(() -> {
            assertTrue(extractor().parse("this is not json at all").isEmpty());
            assertTrue(extractor().parse(null).isEmpty());
            assertTrue(extractor().parse("{\"not\": \"an array\"}").isEmpty());
        });
    }

    @Test
    void unknownCategoryIsDropped() {
        String raw = "[{\"category\":\"NOT_A_REAL_CATEGORY\",\"value\":\"x\",\"polarity\":\"POSITIVE\","
                + "\"permanence\":\"PERMANENT\",\"confidence\":0.9}]";
        assertTrue(extractor().parse(raw).isEmpty());
    }

    @Test
    void missingValueIsDropped() {
        String raw = "[{\"category\":\"COUNTRY\",\"polarity\":\"POSITIVE\",\"confidence\":0.9}]";
        assertTrue(extractor().parse(raw).isEmpty());
    }

    @Test
    void confidenceClampsToZeroOneRange() {
        String raw = "[{\"category\":\"SALARY\",\"value\":\"120K\",\"confidence\":5.0},"
                + "{\"category\":\"SALARY\",\"value\":\"100K\",\"confidence\":-3.0}]";
        List<ExtractedDecision> out = extractor().parse(raw);
        assertEquals(2, out.size());
        assertEquals(0, BigDecimal.ONE.compareTo(out.get(0).confidence()));
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(1).confidence()));
    }

    @Test
    void invalidPolarityAndPermanenceFallBackToSafeDefaults() {
        String raw = "[{\"category\":\"CAREER\",\"value\":\"x\",\"polarity\":\"WHO_KNOWS\","
                + "\"permanence\":\"FOREVER\",\"confidence\":0.8}]";
        ExtractedDecision d = extractor().parse(raw).get(0);
        assertEquals("NEUTRAL", d.polarity());
        assertEquals("TEMPORARY", d.permanence());
    }

    @Test
    void extractDelegatesToGatewayAndParsesResult() {
        AiGatewayService gateway = mock(AiGatewayService.class);
        when(gateway.chat(any(), any())).thenReturn("[{\"category\":\"TECHNOLOGY\",\"value\":\"Kubernetes\","
                + "\"polarity\":\"POSITIVE\",\"permanence\":\"TEMPORARY\",\"confidence\":0.85}]");
        ConversationDecisionExtractor extractor = new ConversationDecisionExtractor(gateway, 2000);
        List<ExtractedDecision> out = extractor.extract("I'm learning Kubernetes");
        assertEquals(1, out.size());
        assertEquals("Kubernetes", out.get(0).value());
    }

    @Test
    void blankMessageNeverCallsGateway() {
        AiGatewayService gateway = mock(AiGatewayService.class);
        ConversationDecisionExtractor extractor = new ConversationDecisionExtractor(gateway, 2000);
        assertTrue(extractor.extract("").isEmpty());
        assertTrue(extractor.extract(null).isEmpty());
        verifyNoInteractions(gateway);
    }
}
