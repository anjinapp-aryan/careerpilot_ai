package ai.careerpilot.submission.answer;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.domain.StarStory;
import ai.careerpilot.submission.answer.AnswerGenerationService.AnswerContext;
import ai.careerpilot.submission.answer.AnswerGenerationService.GeneratedAnswer;
import ai.careerpilot.submission.question.QuestionCategory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnswerGenerationServiceTest {

    private final AiGatewayService ai = mock(AiGatewayService.class);
    private final AnswerGenerationService service = new AnswerGenerationService(ai);

    @Test
    void generateReturnsCannedAnswerFromGateway() {
        when(ai.chat(anyList(), anyString())).thenReturn("Here is my answer.");
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(),
                "Why are you interested in this role?", QuestionCategory.WHY_ROLE, AnswerContext.empty());
        assertEquals("Here is my answer.", answer.answerText());
        assertEquals(QuestionCategory.WHY_ROLE, answer.category());
        assertEquals("Why are you interested in this role?", answer.questionText());
    }

    @Test
    void sourceRefsEmptyWhenContextEmpty() {
        when(ai.chat(anyList(), anyString())).thenReturn("answer");
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, AnswerContext.empty());
        assertEquals("{}", answer.sourceRefs());
    }

    @Test
    void sourceRefsIncludeResumeWhenPresent() {
        when(ai.chat(anyList(), anyString())).thenReturn("answer");
        AnswerContext ctx = new AnswerContext("resume text", null, null, null);
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, ctx);
        assertTrue(answer.sourceRefs().contains("\"resume\":true"));
    }

    @Test
    void sourceRefsIncludePackageSummaryWhenPresent() {
        when(ai.chat(anyList(), anyString())).thenReturn("answer");
        AnswerContext ctx = new AnswerContext(null, "package summary", null, null);
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, ctx);
        assertTrue(answer.sourceRefs().contains("\"applicationPackage\":true"));
    }

    @Test
    void sourceRefsIncludeCompanyBriefWhenPresent() {
        when(ai.chat(anyList(), anyString())).thenReturn("answer");
        AnswerContext ctx = new AnswerContext(null, null, "company brief", null);
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, ctx);
        assertTrue(answer.sourceRefs().contains("\"companyBrief\":true"));
    }

    @Test
    void sourceRefsIncludeStarStoryIdWhenStoryPresent() {
        when(ai.chat(anyList(), anyString())).thenReturn("answer");
        UUID storyId = UUID.randomUUID();
        StarStory story = StarStory.builder().id(storyId).situation("s").task("t").action("a").result("r").build();
        AnswerContext ctx = new AnswerContext(null, null, null, story);
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.LEADERSHIP, ctx);
        assertTrue(answer.sourceRefs().contains("\"starStoryId\":\"" + storyId + "\""));
    }

    @Test
    void exceptionFromGatewayYieldsFallbackAnswerInsteadOfThrowing() {
        when(ai.chat(anyList(), anyString())).thenThrow(new RuntimeException("provider down"));
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, AnswerContext.empty());
        assertTrue(answer.answerText().toLowerCase().contains("unable to generate"));
    }

    @Test
    void exceptionFromGatewayStillPopulatesSourceRefs() {
        when(ai.chat(anyList(), anyString())).thenThrow(new RuntimeException("boom"));
        AnswerContext ctx = new AnswerContext("resume", null, null, null);
        GeneratedAnswer answer = service.generate(UUID.randomUUID(), UUID.randomUUID(), "q",
                QuestionCategory.OTHER, ctx);
        assertTrue(answer.sourceRefs().contains("resume"));
    }

    @Test
    void systemPromptContainsNoFabricationGroundingLanguage() {
        ArgumentCaptor<String> systemCaptor = ArgumentCaptor.forClass(String.class);
        when(ai.chat(anyList(), systemCaptor.capture())).thenReturn("answer");
        service.generate(UUID.randomUUID(), UUID.randomUUID(), "q", QuestionCategory.OTHER, AnswerContext.empty());
        String system = systemCaptor.getValue();
        assertTrue(system.contains("NEVER") || system.contains("Do not invent"),
                "system prompt must contain explicit no-fabrication language");
        assertTrue(system.toLowerCase().contains("only using the information given"));
    }

    @Test
    void userPromptIncludesQuestionAndContextSections() {
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(ai.chat(messagesCaptor.capture(), anyString())).thenReturn("answer");
        AnswerContext ctx = new AnswerContext("my resume text", "pkg summary", "brief text", null);
        service.generate(UUID.randomUUID(), UUID.randomUUID(), "What are your salary expectations?",
                QuestionCategory.SALARY, ctx);
        String userContent = messagesCaptor.getValue().get(0).content();
        assertTrue(userContent.contains("What are your salary expectations?"));
        assertTrue(userContent.contains("my resume text"));
        assertTrue(userContent.contains("pkg summary"));
        assertTrue(userContent.contains("brief text"));
        assertTrue(userContent.contains("SALARY"));
    }

    @Test
    void userPromptRendersStarStorySections() {
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(ai.chat(messagesCaptor.capture(), anyString())).thenReturn("answer");
        StarStory story = StarStory.builder().id(UUID.randomUUID())
                .situation("Sit").task("Task").action("Act").result("Res").build();
        AnswerContext ctx = new AnswerContext(null, null, null, story);
        service.generate(UUID.randomUUID(), UUID.randomUUID(), "q", QuestionCategory.LEADERSHIP, ctx);
        String userContent = messagesCaptor.getValue().get(0).content();
        assertTrue(userContent.contains("Situation: Sit"));
        assertTrue(userContent.contains("Task: Task"));
        assertTrue(userContent.contains("Action: Act"));
        assertTrue(userContent.contains("Result: Res"));
    }

    @Test
    void userPromptSaysNoneAvailableWhenNoStory() {
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        when(ai.chat(messagesCaptor.capture(), anyString())).thenReturn("answer");
        service.generate(UUID.randomUUID(), UUID.randomUUID(), "q", QuestionCategory.OTHER, AnswerContext.empty());
        String userContent = messagesCaptor.getValue().get(0).content();
        assertTrue(userContent.contains("BEST-FIT STAR STORY: none available"));
    }
}
