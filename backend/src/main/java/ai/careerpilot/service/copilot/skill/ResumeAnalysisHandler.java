package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class ResumeAnalysisHandler extends AbstractSkillHandler {

    public ResumeAnalysisHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.RESUME_ANALYSIS; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var resume = retriever.getResumeContext(context.user(), context.contextId());
            context.resume(resume);
            log.debug("Resume analysis context assembled: {}", resume.filename());
        } catch (Exception e) {
            log.warn("Could not load resume for analysis: {}", e.getMessage());
            throw new IllegalArgumentException("No resume found. Please upload one to analyze.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an expert resume coach and career strategist.

            TASK — Resume Analysis: Review the candidate's resume in the CONTEXT and provide
            a prioritized list of concrete, high-impact edits. Focus on:
            - Strong action verbs and quantified achievements
            - Role-aligned keywords and industry terminology
            - Professional formatting and ATS readiness
            - Removing generic filler or weak bullets

            For each improvement, show "before → after" examples. Be specific and actionable.
            Never fabricate experience or credentials.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var resume = context.resume();
        if (resume == null) return "No resume data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Resume Analysis\n");
        sb.append("Filename: ").append(nullSafe(resume.filename())).append("\n");
        sb.append("Current Resume Score: ").append(orNa(resume.resumeScore())).append("/100\n");
        sb.append("Current ATS Score: ").append(orNa(resume.atsScore())).append("/100\n");
        sb.append("Extracted Skills: ").append(truncate(nullSafe(resume.skillsJson()), MAX_SKILL_CHARS)).append("\n");
        sb.append("\n----- RESUME TEXT -----\n");
        sb.append(truncate(nullSafe(resume.parsedText()), MAX_RESUME_CHARS));

        return sb.toString();
    }
}
