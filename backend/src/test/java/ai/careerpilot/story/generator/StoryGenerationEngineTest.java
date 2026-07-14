package ai.careerpilot.story.generator;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.extractor.StoryExtractionEngine.RawMaterial;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StoryGenerationEngineTest {

    private final AiGatewayService gateway = mock(AiGatewayService.class);

    @Test
    void disabledReturnsSkeletonDraftWithoutCallingGateway() {
        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, false);
        var draft = engine.generate(StoryType.LEADERSHIP, null, null);
        assertNotNull(draft.situation());
        verifyNoInteractions(gateway);
    }

    @Test
    void parsesWellFormedJsonResponse() {
        String json = "{\"situation\":\"S\",\"task\":\"T\",\"action\":\"A\",\"result\":\"R\","
                + "\"reflection\":\"Ref\",\"lessonsLearned\":\"L\",\"skillsUsed\":\"java\","
                + "\"technologiesUsed\":\"kafka\",\"businessImpact\":\"saved money\"}";
        when(gateway.chat(anyList(), anyString())).thenReturn(json);

        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, true);
        var draft = engine.generate(StoryType.SUCCESS, new RawMaterial("resume text", "java", List.of(), List.of()), "hint");

        assertEquals("S", draft.situation());
        assertEquals("T", draft.task());
        assertEquals("R", draft.result());
        assertEquals("java", draft.skillsUsed());
    }

    @Test
    void handlesJsonWrappedInProseFencing() {
        String messy = "Here is the story:\n```json\n{\"situation\":\"S\",\"task\":\"T\",\"action\":\"A\","
                + "\"result\":\"R\",\"reflection\":\"\",\"lessonsLearned\":\"\",\"skillsUsed\":\"\","
                + "\"technologiesUsed\":\"\",\"businessImpact\":\"\"}\n```";
        when(gateway.chat(anyList(), anyString())).thenReturn(messy);

        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, true);
        var draft = engine.generate(StoryType.SUCCESS, null, null);
        assertEquals("S", draft.situation());
    }

    @Test
    void gatewayFailureFallsBackToSkeletonDraft() {
        when(gateway.chat(anyList(), anyString())).thenThrow(new RuntimeException("boom"));
        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, true);
        var draft = engine.generate(StoryType.FAILURE, null, null);
        assertNotNull(draft.situation());
        assertTrue(draft.situation().toLowerCase().contains("failure"));
    }

    @Test
    void unparsableResponseFallsBackToSkeletonDraft() {
        when(gateway.chat(anyList(), anyString())).thenReturn("not json at all");
        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, true);
        var draft = engine.generate(StoryType.INNOVATION, null, null);
        assertNotNull(draft.situation());
    }

    @Test
    void promptIncludesResumeAndHintContext() {
        when(gateway.chat(anyList(), anyString())).thenReturn("{}");
        StoryGenerationEngine engine = new StoryGenerationEngine(gateway, true);
        engine.generate(StoryType.SUCCESS, new RawMaterial("my resume text", "java,go",
                List.of("Application status=OFFER"), List.of("Acme (industry=Tech)")), "focus on scale");

        ArgumentCaptor<List<ChatMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(gateway).chat(captor.capture(), anyString());
        String userMessage = captor.getValue().get(0).content();
        assertTrue(userMessage.contains("my resume text"));
        assertTrue(userMessage.contains("focus on scale"));
    }
}
