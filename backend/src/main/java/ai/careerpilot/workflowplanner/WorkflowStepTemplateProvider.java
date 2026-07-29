package ai.careerpilot.workflowplanner;

import java.util.List;

/**
 * Phase 8 — supplies the ordered step blueprint for a {@link WorkflowType} (e.g. the Resume
 * Workflow's Analyze → ATS Optimize → Generate Improvements → Approval steps). This is domain
 * content, not workflow existence/versioning — the latter always goes through {@link
 * WorkflowSelector}/the Workflow Registry; this is the seam a future phase could make
 * registry-driven (e.g. steps stored in {@code workflow_definition.agent_configuration}) without
 * changing {@link WorkflowPlanner}'s contract.
 */
public interface WorkflowStepTemplateProvider {

    List<WorkflowStep> stepsFor(WorkflowType type);
}
