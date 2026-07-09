-- CareerPilot AI — Phase 7.4: Auto Apply Engine
-- Additive only, ships DARK (application.auto.enabled=false). NOT applied by this work.
-- Audit of every submission attempt the agent makes. With no credentialed provider integration,
-- every row is HUMAN_REVIEW today — the agent never records a fabricated SUBMITTED.

CREATE TABLE IF NOT EXISTS application_submission (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    job_id              UUID NOT NULL,
    provider            VARCHAR(48),
    status              VARCHAR(16) NOT NULL,   -- SUBMITTED | HUMAN_REVIEW | FAILED
    external_reference  VARCHAR(255),
    reason              TEXT,
    resume_tailoring_id UUID,
    attempts            INT NOT NULL DEFAULT 1,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_submission_user_job ON application_submission(user_id, job_id, created_at DESC);
