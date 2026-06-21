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

            TASK — Explain Workflow Results: The CONTEXT contains an AI workflow run with its
            current lifecycle status, scores, and agent state. The 8-stage pipeline is:
            Resume Intelligence → Job Discovery → ATS Optimization → Interview Preparation →
            Career Strategy → Salary Intelligence → Human Approval → Application Tracking.

            FIRST, anchor your answer to the run's current Status (shown in CONTEXT). Be specific
            to that state — never give a generic "I can't tell" answer:
            - INTERRUPTED / WAITING_APPROVAL: the run is PAUSED at the Human Approval gate and
              needs the user's decision. Application Tracking has NOT run yet. Tell them it is
              awaiting their approval and summarise what they're approving (the scores/insights so far).
            - REJECTED: the user rejected the run at the Human Approval gate, so it stopped BEFORE
              Application Tracking. Explain it was halted at the final gate and what to change before re-running.
            - FAILED: a stage failed. Identify which stage from the ERROR / agent state and explain
              whether it's an input issue or a system issue, and how to recover or re-run.
            - COMPLETED: all 8 stages finished. Summarise the results and highest-leverage next steps.
            - RUNNING: the pipeline is still executing; explain what's done so far.

            THEN, where scores exist, explain what each means, why it's at its level, and the
            highest-leverage improvements. Ground every statement in the CONTEXT — do not fabricate.
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
