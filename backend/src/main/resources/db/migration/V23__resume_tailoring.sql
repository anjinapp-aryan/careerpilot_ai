-- CareerPilot AI — Phase 2D.1: Resume Tailoring Engine
-- Additive only. Two new tables; no existing table (resumes, resume_versions, job_recommendations,
-- recommendation_audit, applications) is touched. Gated DARK by resume.tailoring.enabled=false and
-- resume.tailoring.trigger-on-approve.enabled=false — no rows are ever written until explicitly
-- flipped on. The original resume (resumes.s3_key / parsed_text) is never modified by this feature.
--
-- NOTE: same Neon hand-apply convention as V4-V22 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS resume_tailoring (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    original_resume_id        UUID NOT NULL REFERENCES resumes(id),
    -- Lineage for reproducibility (Phase 2D.1 decision): every input snapshot this tailoring was
    -- generated against. recommendation_audit_id is the full-fidelity anchor (score breakdown +
    -- profile_version + decision) rather than inventing a parallel "recommendation version" concept.
    recommendation_audit_id   UUID REFERENCES recommendation_audit(id),
    candidate_profile_version UUID REFERENCES candidate_profile_versions(id),
    -- candidate_behavior_profile has no version table of its own (it's a singleton row per user,
    -- continuously overwritten) — its "version" is its updated_at snapshot at generation time.
    behavior_profile_version  TIMESTAMPTZ,
    -- Plain integer, scoped to (user_id, job_id); rendered as "v1.N" at the API/DTO layer only.
    tailoring_version         INTEGER NOT NULL,
    tailored_resume_text      TEXT NOT NULL,
    tailored_resume_s3_key    TEXT,
    ats_before                INTEGER,
    ats_after                 INTEGER,
    improvement_score         INTEGER,
    confidence_score          NUMERIC(5,2),
    status                    VARCHAR(20) NOT NULL DEFAULT 'GENERATED', -- GENERATED | REJECTED | APPROVED
    model_used                VARCHAR(60),
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_user_job
    ON resume_tailoring(user_id, job_id, tailoring_version DESC);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_user_history
    ON resume_tailoring(user_id, created_at DESC);

CREATE TABLE IF NOT EXISTS resume_tailoring_audit (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id       UUID REFERENCES resume_tailoring(id),
    tailoring_version         INTEGER,
    candidate_profile_version UUID,
    recommendation_audit_id   UUID,
    improvement_score         INTEGER,
    outcome                   VARCHAR(24) NOT NULL, -- GENERATED | VALIDATION_REJECTED | CACHE_HIT | ERROR
    reason                    TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_resume_tailoring_audit_user
    ON resume_tailoring_audit(user_id, created_at DESC);
