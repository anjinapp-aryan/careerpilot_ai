-- CareerPilot AI — Phase 2D.2: ATS Optimization Engine
-- Additive only. Analyzes a tailored resume (resume_tailoring, from 2D.1) against its job and
-- produces a deterministic ATS score + LLM-generated matched/missing keywords and optimization
-- suggestions. Immutable-append like resume_tailoring: re-running adds a new row, "latest" reads
-- order by created_at DESC. No existing table is touched.
--
-- NOTE: same Neon hand-apply convention as V4-V24 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS resume_ats_analysis (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id UUID NOT NULL REFERENCES resume_tailoring(id) ON DELETE CASCADE,
    ats_score           INTEGER NOT NULL,
    matched_keywords    TEXT,
    missing_keywords    TEXT,
    suggestions         TEXT,
    confidence_score    NUMERIC(5,2),
    model_used          VARCHAR(60),
    status              VARCHAR(20) NOT NULL DEFAULT 'GENERATED', -- GENERATED | ERROR
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ats_analysis_tailoring
    ON resume_ats_analysis(resume_tailoring_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ats_analysis_user_job
    ON resume_ats_analysis(user_id, job_id, created_at DESC);

CREATE TABLE IF NOT EXISTS ats_optimization_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id UUID NOT NULL REFERENCES resume_tailoring(id) ON DELETE CASCADE,
    ats_analysis_id     UUID REFERENCES resume_ats_analysis(id),
    source              VARCHAR(20) NOT NULL, -- MANUAL | TAILORING_TRIGGER
    status              VARCHAR(20) NOT NULL DEFAULT 'QUEUED', -- QUEUED | RUNNING | SUCCEEDED | FAILED
    error_reason        TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_ats_jobs_status
    ON ats_optimization_jobs(status, created_at);
CREATE INDEX IF NOT EXISTS idx_ats_jobs_user
    ON ats_optimization_jobs(user_id, created_at DESC);
