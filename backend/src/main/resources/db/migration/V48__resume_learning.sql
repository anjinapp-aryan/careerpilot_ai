-- CareerPilot AI — Phase 6.5: Adaptive Resume Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- resume_version is a proxy identifier (the originating resume_tailoring_jobs.resume_tailoring_id,
-- as text) since neither ResumeTailoringJob nor ResumeAtsAnalysis carries a human version string —
-- see deliverable notes for this mapping choice.

CREATE TABLE IF NOT EXISTS resume_learning (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    resume_version  VARCHAR(128) NOT NULL,
    applications    INT NOT NULL DEFAULT 0,
    interviews      INT NOT NULL DEFAULT 0,
    offers          INT NOT NULL DEFAULT 0,
    ats_score_avg   NUMERIC(6,2),
    interview_rate  NUMERIC(6,4),
    offer_rate      NUMERIC(6,4),
    is_best_version BOOLEAN NOT NULL DEFAULT false,
    computed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, resume_version)
);
CREATE INDEX IF NOT EXISTS idx_resume_learning_user ON resume_learning(user_id, offer_rate DESC);
