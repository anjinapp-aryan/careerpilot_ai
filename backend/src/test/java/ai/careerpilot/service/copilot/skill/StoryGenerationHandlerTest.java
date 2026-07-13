package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.story.api.StoryCopilotContextService;
import ai.careerpilot.story.api.StoryCopilotContextService.StoryCopilotContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryGenerationHandlerTest {

    private final CareerContextRetriever retriever = mock(CareerContextRetriever.class);
    private final StoryCopilotContextService storyContext = mock(StoryCopilotContextService.class);
    private final StoryGenerationHandler handler = new StoryGenerationHandler(retriever, storyContext);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "USER");

    @Test
    void skillIsGenerateStarStory() {
        assertEquals(CopilotSkill.GENERATE_STAR_STORY, handler.skill());
    }

    @Test
    void assembleContextToleratesMissingResume() throws Exception {
        when(storyContext.forUser(user.userId())).thenReturn(new StoryCopilotContext(true, List.of()));
        when(retriever.getResumeContext(user, null)).thenThrow(new IllegalArgumentException("no resume"));
        SkillContext ctx = new SkillContext(user, "generate a leadership star story", null, "workflow");
        assertDoesNotThrow(() -> handler.assembleContext(ctx));
        assertNotNull(ctx.story());
    }

    @Test
    void systemPromptMentionsGenerateForPlainMessage() {
        SkillContext ctx = new SkillContext(user, "generate a star story about ownership", null, "page");
        assertTrue(handler.systemPrompt(ctx).contains("Generate STAR Story"));
    }

    @Test
    void systemPromptMentionsImproveForImproveMessage() {
        SkillContext ctx = new SkillContext(user, "improve my incident story", null, "page");
        assertTrue(handler.systemPrompt(ctx).contains("Improve STAR Story"));
    }

    @Test
    void systemPromptMentionsConvertForBulletMessage() {
        SkillContext ctx = new SkillContext(user, "convert this resume bullet into a star story", null, "page");
        assertTrue(handler.systemPrompt(ctx).contains("Convert Resume Bullet"));
    }

    @Test
    void contextBlockIncludesUserMessage() {
        SkillContext ctx = new SkillContext(user, "generate a leadership story", null, "page");
        assertTrue(handler.contextBlock(ctx).contains("generate a leadership story"));
    }
}
