-- CareerPilot AI — Phase 2D.1.1: Resume Tailoring production hardening
-- Additive only. One new table tracking async job status for the tailoring engine
-- (bounded-executor pipeline). No existing table (resume_tailoring, resume_tailoring_audit,
-- recommendation_audit, jobs, users) is touched.
--
-- NOTE: same Neon hand-apply convention as V4-V23 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS resume_tailoring_jobs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                 UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                  UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id     UUID REFERENCES resume_tailoring(id),
    recommendation_audit_id UUID REFERENCES recommendation_audit(id),
    source                  VARCHAR(20) NOT NULL, -- MANUAL | APPROVE_TRIGGER
    status                  VARCHAR(20) NOT NULL DEFAULT 'QUEUED', -- QUEUED | RUNNING | SUCCEEDED | FAILED
    error_reason            TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_jobs_status
    ON resume_tailoring_jobs(status, created_at);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_jobs_user
    ON resume_tailoring_jobs(user_id, created_at DESC);
