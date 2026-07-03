-- CareerPilot AI — Phase 2C-4: recommendation feedback + reason tracking (Step 6)
-- Additive only. Captures every explicit user reaction to a recommendation (approve/reject/ignore/
-- save/apply-later) plus an optional free-text reason, so Phase 2C-5's candidate_behavior_profile can
-- learn preference patterns from real behavior. Append-only; one row per reaction.
--
-- Gated DARK by recommendation.feedback.enabled=false → no rows are ever written until explicitly on.
-- No existing table or flow is touched.
--
-- NOTE: same Neon hand-apply convention as V4–V20 (Flyway baselines; idempotent DDL).

CREATE TABLE IF NOT EXISTS recommendation_feedback (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id      UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    action      VARCHAR(20) NOT NULL,   -- APPROVE | REJECT | IGNORE | SAVE | APPLY_LATER
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_recommendation_feedback_user ON recommendation_feedback(user_id, created_at DESC);
