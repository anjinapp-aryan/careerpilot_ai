-- CareerPilot AI — Phase 1.5: Recommendation scoring audit trail
-- Additive only. Persists the per-job scoring breakdown that JobScoring.scoreV2() already
-- computes in memory (JobMatchingService.refreshForUser), so a future explainability surface
-- can answer "why did this job score X for this user" without re-running the matcher.
--
-- Gated DARK by candidate.recommendation.audit-enabled=false → no rows are ever written until
-- explicitly flipped on. Purely additive side effect after scoring; never affects the returned
-- recommendation.
--
-- NOTE: same Neon hand-apply convention as V4–V14 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS recommendation_audit (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id           UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    profile_version  UUID,
    profile_source   VARCHAR(20) NOT NULL,
    skill_score      INT NOT NULL,
    role_score       INT NOT NULL,
    preference_score INT NOT NULL,
    location_score   INT NOT NULL,
    visa_score       INT NOT NULL,
    salary_score     INT NOT NULL,
    final_score      INT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_recommendation_audit_user ON recommendation_audit(user_id, created_at DESC);
