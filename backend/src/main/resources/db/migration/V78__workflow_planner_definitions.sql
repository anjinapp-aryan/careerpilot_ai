-- Enterprise Workflow Planner, Phase 8. Ten additional workflow_definition rows (Workflow
-- Registry, Phase 4 table) for the WorkflowType values ai.careerpilot.workflowplanner covers but
-- Phase 4's original five seeded rows (V75) didn't. The existing RESUME_OPTIMIZATION_V1/
-- JOB_DISCOVERY_V1/INTERVIEW_PREPARATION_V1/SKILL_ANALYSIS_V1/CAREER_STRATEGY_V1 rows are reused
-- as-is for WorkflowType.RESUME/JOB_DISCOVERY/INTERVIEW/LEARNING/CAREER_STRATEGY — see
-- WorkflowType.registryWorkflowType(). Metadata only, same as V75; registering a definition never
-- executes anything.
INSERT INTO workflow_definition (workflow_id, name, description, version, workflow_type, required_capabilities) VALUES
    ('ATS_V1', 'ATS Optimization', 'Scores and optimizes a resume against a specific job''s ATS parser.', 'v1', 'ATS', '["RESUME_ANALYSIS"]'),
    ('SALARY_V1', 'Salary Intelligence', 'Benchmarks compensation and produces a negotiation strategy.', 'v1', 'SALARY', '[]'),
    ('COMPANY_INTELLIGENCE_V1', 'Company Intelligence', 'Builds a company knowledge profile from available signals.', 'v1', 'COMPANY_INTELLIGENCE', '[]'),
    ('OFFER_EVALUATION_V1', 'Offer Evaluation', 'Evaluates an offer against market percentiles and mission goals.', 'v1', 'OFFER_EVALUATION', '[]'),
    ('VISA_V1', 'Visa Assessment', 'Assesses visa/sponsorship feasibility for a target country.', 'v1', 'VISA', '[]'),
    ('RELOCATION_V1', 'Relocation Planning', 'Plans a relocation timeline and cost-of-living comparison.', 'v1', 'RELOCATION', '[]'),
    ('MISSION_PROGRESS_V1', 'Mission Progress Review', 'Reviews mission progress against the active strategy plan.', 'v1', 'MISSION_PROGRESS', '["CAREER_STRATEGY"]'),
    ('LINKEDIN_V1', 'LinkedIn Optimization', 'Reviews and improves a LinkedIn profile for the target role.', 'v1', 'LINKEDIN', '[]'),
    ('NETWORKING_V1', 'Networking Outreach', 'Identifies and drafts outreach for relevant networking contacts.', 'v1', 'NETWORKING', '[]'),
    ('PORTFOLIO_V1', 'Portfolio Review', 'Reviews a candidate''s portfolio/project work against the target role.', 'v1', 'PORTFOLIO', '[]');
