-- Phase 10 — Skill Gap Intelligence Workflow, the first business workflow built on the LangGraph
-- Workflow Runtime foundation (Phase 9). Two additive changes, no existing table touched:
--
-- 1. One workflow_definition row (Workflow Registry, Phase 4 table) so this workflow is
--    discoverable metadata, same "registering never executes anything" discipline as V75/V78.
-- 2. A new skill_gap_analysis table — this workflow's own persistence + history, mirroring the
--    shape of every prior async-job table in this codebase (resume_tailoring_jobs, etc.):
--    QUEUED/RUNNING/SUCCEEDED/FAILED status, one row per run, result stored as JSONB.

INSERT INTO workflow_definition (workflow_id, name, description, version, workflow_type, required_capabilities)
VALUES (
    'SKILL_GAP_INTELLIGENCE_V1',
    'Skill Gap Intelligence',
    'Compares a candidate''s current profile against market expectations for their mission''s target role and country, producing a prioritized skill gap analysis, learning roadmap, and mission readiness score.',
    'v1',
    'SKILL_GAP_INTELLIGENCE',
    '["RESUME_ANALYSIS"]'
);

CREATE TABLE skill_gap_analysis (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id      UUID NOT NULL REFERENCES career_mission(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL,
    workflow_id     VARCHAR(100) NOT NULL,
    execution_id    VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    result          JSONB,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    CONSTRAINT uq_skill_gap_analysis_execution_id UNIQUE (execution_id)
);

CREATE INDEX idx_skill_gap_analysis_mission_created ON skill_gap_analysis (mission_id, created_at DESC);
CREATE INDEX idx_skill_gap_analysis_user ON skill_gap_analysis (user_id);
