package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class AtsAnalysisHandler extends AbstractSkillHandler {

    public AtsAnalysisHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.ATS_ANALYSIS; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var resume = retriever.getResumeContext(context.user(), context.contextId());
            context.resume(resume);
            log.debug("ATS analysis context assembled for: {}", resume.filename());
        } catch (Exception e) {
            log.warn("Could not load resume for ATS analysis: {}", e.getMessage());
            throw new IllegalArgumentException("No resume found. Please upload one for ATS analysis.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an ATS (Applicant Tracking System) expert.

            TASK — ATS Analysis: Evaluate how well the resume in CONTEXT will pass through
            Applicant Tracking Systems. Analyze:
            - Missing keywords and required skills
            - Formatting risks (tables, graphics, unusual fonts)
            - Section structure and organization
            - File format and parseability issues
            - Numerical data and metrics clarity

            End with an estimated ATS readiness score (Low/Medium/High) and the top 3
            fixes to implement immediately.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var resume = context.resume();
        if (resume == null) return "No resume data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — ATS Analysis\n");
        sb.append("Filename: ").append(nullSafe(resume.filename())).append("\n");
        sb.append("Current ATS Score: ").append(orNa(resume.atsScore())).append("/100\n");
        sb.append("Extracted Skills (ATS-parsed): ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n");
        sb.append("\n----- RESUME TEXT -----\n");
        sb.append(truncate(nullSafe(resume.parsedText()), MAX_RESUME_CHARS));

        return sb.toString();
    }
}
