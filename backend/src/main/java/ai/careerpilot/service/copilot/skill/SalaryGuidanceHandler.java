package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class SalaryGuidanceHandler extends AbstractSkillHandler {

    public SalaryGuidanceHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.SALARY_GUIDANCE; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var job = retriever.getJobContext(context.user(), context.contextId());
            context.job(job);
            var resume = retriever.getResumeContext(context.user(), null);
            context.resume(resume);
            log.debug("Salary guidance context assembled for: {}", job.title());
        } catch (Exception e) {
            log.warn("Could not load context for salary guidance: {}", e.getMessage());
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a compensation strategist and market analyst.

            TASK — Salary Guidance: Based on the job, candidate profile, and market data
            in CONTEXT, provide detailed salary guidance. Cover:
            - Realistic salary range for this role in this market
            - How the candidate's experience affects their position in the range
            - Negotiation strategies and talking points
            - Benefits and equity considerations
            - Red flags in compensation offers

            Be realistic based on market data and the candidate's profile.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var job = context.job();
        var resume = context.resume();

        StringBuilder sb = new StringBuilder("CONTEXT — Salary Guidance\n\n");

        if (job != null) {
            sb.append("TARGET JOB\n");
            sb.append("Title: ").append(nullSafe(job.title())).append("\n");
            sb.append("Company: ").append(nullSafe(job.company())).append("\n");
            sb.append("Location: ").append(nullSafe(job.location())).append("\n");
            sb.append("Posted Salary Range: ").append(nullSafe(job.salaryRange())).append("\n\n");
        }

        if (resume != null) {
            sb.append("CANDIDATE PROFILE\n");
            sb.append("Resume: ").append(nullSafe(resume.filename())).append("\n");
            sb.append("Skills: ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n");
        }

        return sb.toString();
    }
}
