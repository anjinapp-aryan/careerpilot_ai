package ai.careerpilot.workflowplanner;

/**
 * Phase 8 — Enterprise Workflow Planner. The 15 workflow families the planner can produce a plan
 * for. {@link #registryWorkflowType()} is the {@code workflow_definition.workflow_type} value
 * this maps to in the existing Workflow Registry (Phase 4) — five of these (RESUME, JOB_DISCOVERY,
 * INTERVIEW, LEARNING, CAREER_STRATEGY) intentionally reuse Phase 4's original five seeded
 * definitions (whose {@code workflow_type} values predate this enum and use slightly different
 * names, e.g. {@code RESUME_OPTIMIZATION}) rather than re-registering them; the other ten are
 * seeded fresh by {@code V78__workflow_planner_definitions.sql}. The planner never hardcodes a
 * definition's existence/version/capabilities — see {@link WorkflowSelector}.
 */
public enum WorkflowType {
    RESUME("RESUME_OPTIMIZATION"),
    JOB_DISCOVERY("JOB_DISCOVERY"),
    ATS("ATS"),
    INTERVIEW("INTERVIEW_PREPARATION"),
    LEARNING("SKILL_ANALYSIS"),
    SALARY("SALARY"),
    COMPANY_INTELLIGENCE("COMPANY_INTELLIGENCE"),
    OFFER_EVALUATION("OFFER_EVALUATION"),
    VISA("VISA"),
    RELOCATION("RELOCATION"),
    MISSION_PROGRESS("MISSION_PROGRESS"),
    CAREER_STRATEGY("CAREER_STRATEGY"),
    LINKEDIN("LINKEDIN"),
    NETWORKING("NETWORKING"),
    PORTFOLIO("PORTFOLIO");

    private final String registryWorkflowType;

    WorkflowType(String registryWorkflowType) {
        this.registryWorkflowType = registryWorkflowType;
    }

    /** The {@code workflow_definition.workflow_type} value the Workflow Registry stores this under. */
    public String registryWorkflowType() {
        return registryWorkflowType;
    }
}
