-- Workflow Registry, Phase 4. A DECLARATIVE CATALOG of reusable workflow types — distinct from
-- the existing workflow_runs table (LangGraph thread-execution tracking, WorkflowService/
-- WorkflowRun) which this does NOT replace or duplicate. workflow_execution here is a thin audit
-- record of a registry-triggered run; real AI-pipeline execution continues to go exclusively
-- through the existing WorkflowService (per CLAUDE.md: "do not expose the agent service to the
-- frontend — do not funnel it through this service" is the existing rule this registry respects,
-- not overrides). See WorkflowRegistryService/WorkflowExecutionService javadocs for what's real
-- vs. deliberately deferred, matching the existing DeferredAgentTaskExecutor honesty discipline.
CREATE TABLE workflow_definition (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    version VARCHAR(20) NOT NULL,
    workflow_type VARCHAR(50) NOT NULL,
    agent_configuration JSONB,
    required_capabilities JSONB,
    required_tools JSONB,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workflow_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_definition_id UUID NOT NULL REFERENCES workflow_definition(id),
    user_id UUID NOT NULL,
    mission_id UUID REFERENCES career_mission(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    notes TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workflow_execution_user ON workflow_execution (user_id, created_at DESC);

INSERT INTO workflow_definition (workflow_id, name, description, version, workflow_type, required_capabilities) VALUES
    ('JOB_DISCOVERY_V1', 'Job Discovery', 'Collects, enriches, deduplicates, matches, and ranks jobs.', 'v1', 'JOB_DISCOVERY', '["JOB_RECOMMENDATION"]'),
    ('RESUME_OPTIMIZATION_V1', 'Resume Optimization', 'Parses, extracts skills, ATS-optimizes, and improves a resume.', 'v1', 'RESUME_OPTIMIZATION', '["RESUME_ANALYSIS"]'),
    ('INTERVIEW_PREPARATION_V1', 'Interview Preparation', 'Generates questions, runs mock interviews, evaluates answers, builds STAR stories.', 'v1', 'INTERVIEW_PREPARATION', '["INTERVIEW_PREPARATION"]'),
    ('SKILL_ANALYSIS_V1', 'Skill Gap Analysis', 'Analyzes current skills against market demand and plans learning.', 'v1', 'SKILL_ANALYSIS', '["LEARNING_HELP"]'),
    ('CAREER_STRATEGY_V1', 'Career Strategy', 'Produces a career trajectory and probability assessment.', 'v1', 'CAREER_STRATEGY', '["CAREER_STRATEGY"]');
