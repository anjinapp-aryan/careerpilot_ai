package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class InterviewPrepHandler extends AbstractSkillHandler {

    public InterviewPrepHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.INTERVIEW_PREPARATION; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var app = retriever.getApplicationContext(context.user(), context.contextId());
            context.application(app);
            log.debug("Interview prep context assembled");
        } catch (Exception e) {
            log.warn("Could not load application for interview prep: {}", e.getMessage());
            throw new IllegalArgumentException("No application found for interview preparation.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an expert interview coach.

            TASK — Interview Preparation: Using the application, job, and resume in CONTEXT,
            assess the likelihood of advancing to / succeeding in interviews. Provide:
            - 5-7 most likely interview questions specific to this role and company
            - Your strengths to lean on and how to communicate them
            - Weak spots to prepare for and how to address them
            - Probability of advancing to next round (honest assessment)
            - 30-second elevator pitch tailored to the role

            Be realistic and constructive. Highlight both opportunities and risks.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var app = context.application();
        if (app == null) return "No application data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Interview Preparation\n");
        sb.append("Role Status: ").append(nullSafe(app.status())).append("\n");
        sb.append("Match Score: ").append(orNa(app.matchScore())).append("/100\n");
        sb.append("Days Since Application: ");
        if (app.createdAt() != null) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(app.createdAt(), java.time.Instant.now());
            sb.append(days);
        } else {
            sb.append("unknown");
        }
        sb.append("\n\n");

        if (app.job() != null) {
            sb.append("TARGET JOB\n");
            sb.append("Title: ").append(nullSafe(app.job().title())).append(" @ ").append(nullSafe(app.job().company())).append("\n");
            sb.append("Required Skills: ").append(truncate(nullSafe(app.job().requiredSkillsJson()), MAX_SKILL_CHARS)).append("\n");
            sb.append("\n----- JOB DESCRIPTION -----\n");
            sb.append(truncate(nullSafe(app.job().description()), MAX_JOB_CHARS)).append("\n\n");
        }

        if (app.resume() != null) {
            sb.append("CANDIDATE RESUME\n");
            sb.append("Skills: ").append(truncate(nullSafe(app.resume().skillsJson()), MAX_SKILL_CHARS)).append("\n");
            sb.append("\n----- RESUME -----\n");
            sb.append(truncate(nullSafe(app.resume().parsedText()), MAX_RESUME_CHARS));
        }

        return sb.toString();
    }
}
