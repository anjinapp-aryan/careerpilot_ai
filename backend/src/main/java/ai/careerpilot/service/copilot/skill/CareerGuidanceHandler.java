package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class CareerGuidanceHandler extends AbstractSkillHandler {

    public CareerGuidanceHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.CAREER_GUIDANCE; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var profile = retriever.getUserProfileContext(context.user());
            context.userProfile(profile);
            log.debug("Career guidance context assembled for user");
        } catch (Exception e) {
            log.warn("Could not load user profile for career guidance: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an experienced career mentor and strategist.

            TASK — Career Guidance: Based on the user's profile, resume, and application
            history in CONTEXT, provide thoughtful career guidance. Address:
            - Career path analysis and next steps
            - Growth opportunities in their domain
            - Market trends and skill demand
            - Long-term career strategy
            - Work-life balance and fulfillment considerations

            Be supportive, realistic, and data-informed. Avoid generic advice.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var profile = context.userProfile();
        if (profile == null) return "No user profile data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Career Guidance\n");
        sb.append("Resume Count: ").append(profile.resumeCount()).append("\n");
        sb.append("Applications Tracked: ").append(profile.applicationCount()).append("\n");
        sb.append("AI Workflow Runs: ").append(profile.workflowCount()).append("\n\n");

        if (!profile.resumes().isEmpty()) {
            sb.append("RECENT RESUMES\n");
            profile.resumes().stream().limit(3).forEach(r ->
                sb.append("- ").append(nullSafe(r.filename())).append(" (Score: ").append(orNa(r.resumeScore())).append(")\n")
            );
            sb.append("\n");
        }

        if (!profile.applications().isEmpty()) {
            sb.append("RECENT APPLICATIONS\n");
            profile.applications().stream().limit(5).forEach(a ->
                sb.append("- ").append(nullSafe(a.status())).append(" (Match: ").append(orNa(a.matchScore())).append(")\n")
            );
        }

        return sb.toString();
    }
}
