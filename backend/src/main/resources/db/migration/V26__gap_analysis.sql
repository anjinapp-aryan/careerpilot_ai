-- CareerPilot AI — Phase 2D.3: Gap Analysis Engine
-- Additive only. Deterministic (non-LLM) comparison of candidate profile + resume + tailored
-- resume against job requirements. One append-only row per analysis; triggered by
-- AtsOptimizedEvent (flag-gated, dark by default). No existing table is touched.
--
-- NOTE: same Neon hand-apply convention as V4-V25 (idempotent DDL).

CREATE TABLE IF NOT EXISTS resume_gap_analysis (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id       UUID NOT NULL REFERENCES resume_tailoring(id) ON DELETE CASCADE,
    resume_ats_analysis_id    UUID REFERENCES resume_ats_analysis(id),
    candidate_profile_version UUID,
    behavior_profile_version  TIMESTAMPTZ,
    missing_skills            TEXT,
    missing_certifications    TEXT,
    missing_cloud             TEXT,
    missing_leadership        TEXT,
    missing_architecture      TEXT,
    missing_domains           TEXT,
    gap_score                 INTEGER NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_gap_analysis_user_job
    ON resume_gap_analysis(user_id, job_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_gap_analysis_tailoring
    ON resume_gap_analysis(resume_tailoring_id, created_at DESC);
