package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.api.StoryCopilotContextService;
import org.springframework.stereotype.Component;

/**
 * Phase 7.15 — Copilot skill covering STAR story generation/improvement in free-text chat form:
 * "generate a leadership STAR story", "improve my incident story", "convert this resume bullet
 * into a STAR story". Collapses what the spec lists as 11 near-identical asks (one per story-type
 * generation, resume-bullet conversion, improve) into a single handler parameterized by the
 * inferred {@link StoryType} and mode (generate vs improve vs convert), read from the free-text
 * message — actual persistence happens through {@code POST /api/story/generate}/{@code /improve};
 * this handler's job is to explain/draft inline in chat, matching how every other skill here is
 * advisory rather than mutating.
 */
@Component
public class StoryGenerationHandler extends AbstractSkillHandler {

    private final StoryCopilotContextService storyContext;

    public StoryGenerationHandler(CareerContextRetriever retriever, StoryCopilotContextService storyContext) {
        super(retriever);
        this.storyContext = storyContext;
    }

    @Override public CopilotSkill skill() { return CopilotSkill.GENERATE_STAR_STORY; }

    @Override
    public void assembleContext(SkillContext context) {
        context.story(storyContext.forUser(context.user().userId()));
        try {
            var resume = retriever.getResumeContext(context.user(), context.contextId());
            context.resume(resume);
        } catch (Exception e) {
            log.debug("No resume available for STAR story generation context: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        StoryType hint = inferType(context.message());
        String mode = inferMode(context.message());
        return """
            You are CareerPilot Copilot, a behavioral-interview coach specializing in the STAR
            (Situation, Task, Action, Result) format, plus Reflection, Lessons Learned, Skills Used,
            Technologies Used, Business Impact, and Evidence.

            TASK — %s%s: Using the resume and existing stories in CONTEXT (if any), draft or improve a
            STAR story. Always structure your answer with clear headers: Situation, Task, Action,
            Result, Reflection, Lessons Learned, Skills Used, Technologies Used, Business Impact. Keep
            each section 2-4 sentences, specific and first-person. If the user pasted a resume bullet,
            expand it into a full story using reasonable, clearly-labeled assumptions. End by inviting
            them to save it via the Story Library so it can be scored and reused.
            """.formatted(mode, hint == null ? "" : " (" + hint.name().replace('_', ' ').toLowerCase() + " story)");
    }

    @Override
    public String contextBlock(SkillContext context) {
        StringBuilder sb = new StringBuilder("CONTEXT — STAR Story Generation\n");
        var resume = context.resume();
        if (resume != null) {
            sb.append("Resume excerpt: ").append(truncate(nullSafe(resume.parsedText()), MAX_RESUME_CHARS)).append("\n");
        }
        var story = context.story();
        if (story != null && story.enabled() && !story.stories().isEmpty()) {
            sb.append("Existing stories (for style/skill consistency): ")
                    .append(story.stories().size()).append(" saved.\n");
        }
        sb.append("User request: ").append(nullSafe(context.message()));
        return sb.toString();
    }

    private static StoryType inferType(String message) {
        if (message == null) return null;
        String lower = message.toLowerCase();
        for (StoryType t : StoryType.values()) {
            if (lower.contains(t.name().replace('_', ' ').toLowerCase())) return t;
        }
        return null;
    }

    private static String inferMode(String message) {
        if (message == null) return "Generate STAR Story";
        String lower = message.toLowerCase();
        if (lower.contains("improve")) return "Improve STAR Story";
        if (lower.contains("bullet") || lower.contains("convert")) return "Convert Resume Bullet Into STAR Story";
        return "Generate STAR Story";
    }
}
