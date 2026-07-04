-- CareerPilot AI — Phase 2D.4: ATS Explainability Engine
-- Additive only. Deterministic explanation of WHY an ATS score came out the way it did:
-- matched vs missing evidence per category, confidence, and actionable recommendations.
-- No LLM scoring (scores/matches are pure arithmetic over persisted rows).
--
-- NOTE: same Neon hand-apply convention as V4-V26 (idempotent DDL).

CREATE TABLE IF NOT EXISTS resume_ats_explanation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_tailoring_id    UUID NOT NULL REFERENCES resume_tailoring(id) ON DELETE CASCADE,
    resume_ats_analysis_id UUID REFERENCES resume_ats_analysis(id),
    gap_analysis_id        UUID REFERENCES resume_gap_analysis(id),
    ats_score              INTEGER,
    matched_skills         TEXT,
    matched_experience     TEXT,
    matched_cloud          TEXT,
    matched_leadership     TEXT,
    matched_architecture   TEXT,
    missing_items          TEXT,
    confidence             NUMERIC(5,2),
    recommendations        TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ats_explanation_user_job
    ON resume_ats_explanation(user_id, job_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ats_explanation_tailoring
    ON resume_ats_explanation(resume_tailoring_id, created_at DESC);
