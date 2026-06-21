package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class SkillsGapHandler extends AbstractSkillHandler {

    public SkillsGapHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.SKILLS_GAP_ANALYSIS; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var job = retriever.getJobContext(context.user(), context.contextId());
            context.job(job);
            var resume = retriever.getResumeContext(context.user(), null);
            context.resume(resume);
            log.debug("Skills gap context assembled");
        } catch (Exception e) {
            log.warn("Could not load context for skills gap analysis: {}", e.getMessage());
            throw new IllegalArgumentException("Unable to load job and/or resume for skills analysis.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, a skills development strategist.

            TASK — Skills Gap Analysis: Compare the candidate's current skills against the
            job requirements in CONTEXT. Identify:
            - Hard skills gaps (technical/domain knowledge)
            - Soft skills gaps (communication, leadership, etc.)
            - Which gaps are critical vs. nice-to-have
            - Learning resources and timelines for each gap
            - Quick wins (gaps closeable in <3 months)
            - Long-term investments (>6 months)

            Prioritize by impact and feasibility.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var job = context.job();
        var resume = context.resume();

        StringBuilder sb = new StringBuilder("CONTEXT — Skills Gap Analysis\n\n");

        if (job != null) {
            sb.append("REQUIRED SKILLS FOR ROLE\n");
            sb.append("Title: ").append(nullSafe(job.title())).append(" @ ").append(nullSafe(job.company())).append("\n");
            sb.append("Required Skills: ").append(truncate(nullSafe(job.requiredSkillsJson()), MAX_SKILL_CHARS)).append("\n\n");
        }

        if (resume != null) {
            sb.append("CANDIDATE CURRENT SKILLS\n");
            sb.append("Resume: ").append(nullSafe(resume.filename())).append("\n");
            sb.append("Current Skills: ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n\n");
            sb.append("----- RESUME EXCERPT -----\n");
            sb.append(truncate(nullSafe(resume.parsedText()), MAX_RESUME_CHARS));
        }

        return sb.toString();
    }
}
