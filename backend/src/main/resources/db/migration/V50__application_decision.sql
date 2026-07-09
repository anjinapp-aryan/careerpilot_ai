-- CareerPilot AI — Phase 7.1: Application Decision Engine
-- Additive only, ships DARK (application.decision.enabled=false). NOT applied by this work.
-- One append-only row per (user, job) decision. Reads existing recommendation / ATS / learning
-- signals only — never writes to job_recommendations, resume_ats_analysis, or any Phase 1–6 table.

CREATE TABLE IF NOT EXISTS application_decision (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL,
    job_id         UUID NOT NULL,
    outcome        VARCHAR(16) NOT NULL,   -- AUTO_APPLY | HUMAN_REVIEW | SAVE | IGNORE
    match_score    INT,
    ats_score      INT,
    learning_boost INT,
    must_apply     BOOLEAN,
    priority       VARCHAR(16),
    reason         TEXT,
    decided_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_decision_user_job ON application_decision(user_id, job_id, decided_at DESC);
