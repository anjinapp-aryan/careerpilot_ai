package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class ApplicationStrategyHandler extends AbstractSkillHandler {

    public ApplicationStrategyHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.APPLICATION_STRATEGY; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var app = retriever.getApplicationContext(context.user(), context.contextId());
            context.application(app);
            log.debug("Application strategy context assembled");
        } catch (Exception e) {
            log.warn("Could not load application for strategy: {}", e.getMessage());
            throw new IllegalArgumentException("No application found.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an application strategy expert.

            TASK — Application Strategy: Based on the application status, timeline, and notes
            in CONTEXT, recommend the next best action. Provide:
            - Optimal timing for the next action
            - Who to contact and how to find them
            - A ready-to-send follow-up message tailored to the current stage
            - Risk assessment and contingency plans

            Be specific and actionable.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var app = context.application();
        if (app == null) return "No application data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Application Strategy\n");
        sb.append("Status: ").append(nullSafe(app.status())).append("\n");
        sb.append("Applied: ").append(app.createdAt() != null ? app.createdAt() : "unknown").append("\n");
        sb.append("Match Score: ").append(orNa(app.matchScore())).append("/100\n");
        sb.append("ATS Score: ").append(orNa(app.atsScore())).append("/100\n");
        sb.append("Next Action Notes: ").append(nullSafe(app.nextAction())).append("\n");
        sb.append("User Notes: ").append(truncate(nullSafe(app.notes()), 2000)).append("\n");

        if (app.job() != null) {
            sb.append("\n----- TARGET JOB -----\n");
            sb.append("Title: ").append(nullSafe(app.job().title())).append(" @ ").append(nullSafe(app.job().company())).append("\n");
            sb.append(truncate(nullSafe(app.job().description()), MAX_JOB_CHARS));
        }

        return sb.toString();
    }
}
