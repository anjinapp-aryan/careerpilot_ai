-- Phase 8.2 — Resume Intelligence Center: per-(user, resume) analysis lifecycle tracking.
-- Deliberately NOT a duplicate of candidate_profiles (the canonical, one-row-per-user AI
-- extraction) — this table only tracks the *status* of individual analyze/re-analyze attempts
-- (ANALYZING/ANALYZED/FAILED), so the UI can show a real lifecycle instead of pretending a
-- resume is "analyzed" just because a profile row exists for the user. NOT_ANALYZED, OUTDATED,
-- and PARTIAL are derived at read time (see ResumeIntelligenceCenterService), not stored here.
CREATE TABLE IF NOT EXISTS resume_analysis_runs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resume_id           UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    status              VARCHAR(20) NOT NULL,      -- ANALYZING | ANALYZED | FAILED
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    duration_ms         BIGINT,
    error_message       TEXT,
    profile_version_id  UUID REFERENCES candidate_profile_versions(id) ON DELETE SET NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_resume_analysis_runs_user_resume
    ON resume_analysis_runs(user_id, resume_id, created_at DESC);
