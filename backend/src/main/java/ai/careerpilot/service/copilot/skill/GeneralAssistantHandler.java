package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

/**
 * Fallback handler for general career questions that don't match a specific skill.
 */
@Component
public class GeneralAssistantHandler extends AbstractSkillHandler {

    public GeneralAssistantHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return null; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var profile = retriever.getUserProfileContext(context.user());
            context.userProfile(profile);
        } catch (Exception e) {
            log.debug("Could not load user profile for general assistance: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an expert career advisor and AI assistant.

            You are the primary AI intelligence layer of CareerPilot AI, an enterprise
            career management platform. You serve as a career coach, resume specialist,
            recruiter, and interview coach all in one.

            Style: concise, specific, and actionable. Prefer short paragraphs and tight
            bullet lists. Use markdown. Never invent facts about the user — rely only on
            the CONTEXT provided. If the context is missing something you need, say so
            and ask one focused follow-up question.

            Help the user with whatever they ask about their career.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var profile = context.userProfile();
        if (profile == null) return "User has no profile data yet.";

        StringBuilder sb = new StringBuilder("CONTEXT — User Career Profile\n");
        sb.append("Resumes: ").append(profile.resumeCount()).append("\n");
        sb.append("Applications: ").append(profile.applicationCount()).append("\n");
        sb.append("Workflow Analyses: ").append(profile.workflowCount()).append("\n");

        return sb.toString();
    }
}
