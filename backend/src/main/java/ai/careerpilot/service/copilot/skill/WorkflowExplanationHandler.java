package ai.careerpilot.service.copilot.skill;

import ai.careerpilot.service.CareerContextRetriever;
import ai.careerpilot.service.copilot.AbstractSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkill;
import ai.careerpilot.service.copilot.SkillContext;
import org.springframework.stereotype.Component;

@Component
public class WorkflowExplanationHandler extends AbstractSkillHandler {

    public WorkflowExplanationHandler(CareerContextRetriever retriever) {
        super(retriever);
    }

    @Override public CopilotSkill skill() { return CopilotSkill.WORKFLOW_EXPLANATION; }

    @Override
    public void assembleContext(SkillContext context) throws Exception {
        try {
            var workflow = retriever.getWorkflowContext(context.user(), context.contextId());
            context.workflow(workflow);
            log.debug("Workflow explanation context assembled");
        } catch (Exception e) {
            log.warn("Could not load workflow for explanation: {}", e.getMessage());
            throw new IllegalArgumentException("No workflow run found.");
        }
    }

    @Override
    public String systemPrompt(SkillContext context) {
        return """
            You are CareerPilot Copilot, an AI workflow expert.

            TASK — Explain Workflow Results: The CONTEXT contains a completed or failed AI
            workflow run with scores and agent state. Explain:
            - What each score means and how it was calculated
            - Why the scores are at their current levels
            - The highest-leverage improvements to increase scores
            - Next steps to strengthen the candidate's profile
            - Any red flags or warnings in the results

            For failed workflows, explain what went wrong and how to recover.
            """;
    }

    @Override
    public String contextBlock(SkillContext context) {
        var workflow = context.workflow();
        if (workflow == null) return "No workflow data available.";

        StringBuilder sb = new StringBuilder("CONTEXT — Workflow Results\n");
        sb.append("Status: ").append(nullSafe(workflow.status())).append("\n");
        sb.append("Target Role: ").append(nullSafe(workflow.targetRole())).append(" (").append(nullSafe(workflow.targetSeniority())).append(")\n");
        sb.append("\nSCORES:\n");
        sb.append("- Resume Score: ").append(orNa(workflow.resumeScore())).append("/100\n");
        sb.append("- ATS Score: ").append(orNa(workflow.atsScore())).append("/100\n");
        sb.append("- Job Match Score: ").append(orNa(workflow.jobMatchScore())).append("/100\n");
        sb.append("- Interview Readiness: ").append(orNa(workflow.interviewReadinessScore())).append("/100\n");

        if (workflow.errorMessage() != null && !workflow.errorMessage().isBlank()) {
            sb.append("\nERROR: ").append(nullSafe(workflow.errorMessage())).append("\n");
        }

        if (workflow.state() != null && !workflow.state().isBlank()) {
            sb.append("\nWORKFLOW STATE (JSON):\n");
            sb.append(truncate(workflow.state(), 8000));
        }

        return sb.toString();
    }
}
