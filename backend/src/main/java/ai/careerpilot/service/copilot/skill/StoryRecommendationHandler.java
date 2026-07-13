package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.story.api.StoryCopilotContextService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 7.15 — Copilot skill: "suggest my best STAR story for this company/role/question" and
 * "what STAR sections am I missing". Read-only over the user's existing story library — no
 * generation happens here (see {@link StoryGenerationHandler} for that).
 */
@Component
public class StoryRecommendationHandler extends AbstractSkillHandler {

    private final StoryCopilotContextService storyContext;

    public StoryRecommendationHandler(CareerContextRetriever retriever, StoryCopilotContextService storyContext) {
        super(retriever);
        this.storyContext = storyContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.SUGGEST_STAR_STORY; }

    @Override
    public void assembleContext(SkillContext context) {
        context.story(storyContext.forUser(context.user().userId()));
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a behavioral-interview coach.

            TASK — Suggest STAR Story: Given the user's existing STAR story library in CONTEXT and
            their message (which may mention a company, role, or interview question), pick the single
            best-fitting story and explain in 2-3 sentences why it fits, referencing its quality score
            and competencies. If no story fits well, say so plainly and suggest what story they should
            build next. Also flag any STAR sections (Situation/Task/Action/Result/Reflection/Lessons
            Learned/Evidence) that are missing from the story you recommend, if the user asked about
            missing sections.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var story = context.story();
        if (story == null || !story.enabled()) {
            return "STAR Story Intelligence is not enabled for this account.";
        }
        List<StarStory> all = story.stories();
        if (all.isEmpty()) {
            return "The user has no saved STAR stories yet.";
        }
        String rendered = all.stream().limit(15).map(this::renderStory).collect(Collectors.joining("\n---\n"));
        return "CONTEXT — STAR Story Library (" + all.size() + " stories)\n" + rendered;
    }

    private String renderStory(StarStory s) {
        return "Title: " + nullSafe(s.getTitle())
                + "\nType: " + (s.getStoryType() == null ? "n/a" : s.getStoryType())
                + "\nQuality Score: " + orNa(s.getQualityScore())
                + "\nCompetencies: " + nullSafe(s.getCompetencies())
                + "\nMissing Sections: " + nullSafe(s.getMissingSections())
                + "\nResult: " + truncate(nullSafe(s.getResult()), 400);
    }
}
