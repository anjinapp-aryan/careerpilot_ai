package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.api.StoryCopilotContextService;
import ai.careerpilot.story.api.StoryCopilotContextService.StoryCopilotContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryRecommendationHandlerTest {

    private final CareerContextRetriever retriever = mock(CareerContextRetriever.class);
    private final StoryCopilotContextService storyContext = mock(StoryCopilotContextService.class);
    private final StoryRecommendationHandler handler = new StoryRecommendationHandler(retriever, storyContext);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "USER");

    @Test
    void skillIsSuggestStarStory() {
        assertEquals(CopilotSkill.SUGGEST_STAR_STORY, handler.skill());
    }

    @Test
    void assembleContextPopulatesStoryField() throws Exception {
        when(storyContext.forUser(user.userId())).thenReturn(new StoryCopilotContext(true, List.of()));
        SkillContext ctx = new SkillContext(user, "suggest my best story", null, "interview");
        handler.assembleContext(ctx);
        assertNotNull(ctx.story());
        assertTrue(ctx.story().enabled());
    }

    @Test
    void contextBlockReportsDisabledWhenFeatureOff() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.story(new StoryCopilotContext(false, List.of()));
        assertTrue(handler.contextBlock(ctx).toLowerCase().contains("not enabled"));
    }

    @Test
    void contextBlockReportsNoStoriesWhenLibraryEmpty() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.story(new StoryCopilotContext(true, List.of()));
        assertTrue(handler.contextBlock(ctx).toLowerCase().contains("no saved"));
    }

    @Test
    void contextBlockRendersStoryDetails() {
        StarStory story = StarStory.builder().id(UUID.randomUUID()).userId(user.userId()).title("Rescued the launch")
                .storyType(StoryType.LEADERSHIP).status(StoryStatus.COMPLETE).source(StorySource.MANUAL)
                .qualityScore(85).competencies("LEADERSHIP").result("Shipped on time").currentVersion(1).build();
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        ctx.story(new StoryCopilotContext(true, List.of(story)));
        String block = handler.contextBlock(ctx);
        assertTrue(block.contains("Rescued the launch"));
        assertTrue(block.contains("LEADERSHIP"));
    }

    @Test
    void systemPromptIsNonEmpty() {
        SkillContext ctx = new SkillContext(user, "msg", null, "page");
        assertFalse(handler.systemPrompt(ctx).isBlank());
    }
}
