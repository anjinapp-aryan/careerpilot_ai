package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class JobMatchHandler extends AbstractSkillHandler {

    public JobMatchHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.JOB_MATCH_ANALYSIS; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var job = retriever.getJobContext(context.user(), context.contextId());
            context.job(job);
            var resume = retriever.getResumeContext(context.user(), null);
            context.resume(resume);
            log.debug("Job match context assembled: {} vs {}", job.title(), resume.filename());
        } catch (Exception e) {
            log.warn("Could not assemble job match context: {}", e.getMessage());
            throw new IllegalArgumentException("Unable to load job and/or resume for matching.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an expert recruiter and talent matcher.

            TASK — Job Match Analysis: Compare the candidate's resume against the target job
            in CONTEXT. Provide a detailed match assessment covering:
            - Technical skills alignment
            - Experience level and seniority match
            - Domain knowledge fit
            - Strongest alignment points (what the candidate excels at for this role)
            - Critical gaps the candidate must address before applying

            Be honest and specific. If it's not a good fit, explain why clearly.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var job = context.job();
        var resume = context.resume();

        StringBuilder sb = new StringBuilder("CONTEXT — Job Match Analysis\n\n");

        if (job != null) {
            sb.append("TARGET JOB\n");
            sb.append("Title: ").append(nullSafe(job.title())).append("\n");
            sb.append("Company: ").append(nullSafe(job.company())).append("\n");
            sb.append("Location: ").append(nullSafe(job.location())).append("\n");
            sb.append("Salary: ").append(nullSafe(job.salaryRange())).append("\n");
            sb.append("Required Skills: ").append(truncate(nullSafe(job.requiredSkillsJson()), MAX_SKILL_CHARS)).append("\n");
            sb.append("\n----- JOB DESCRIPTION -----\n");
            sb.append(truncate(nullSafe(job.description()), MAX_JOB_CHARS)).append("\n\n");
        }

        if (resume != null) {
            sb.append("CANDIDATE RESUME\n");
            sb.append("Filename: ").append(nullSafe(resume.filename())).append("\n");
            sb.append("Extracted Skills: ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n");
            sb.append("\n----- RESUME TEXT -----\n");
            sb.append(truncate(nullSafe(resume.parsedText()), MAX_RESUME_CHARS));
        }

        return sb.toString();
    }
}
