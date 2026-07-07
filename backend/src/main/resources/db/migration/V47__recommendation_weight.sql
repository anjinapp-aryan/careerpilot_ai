-- CareerPilot AI — Phase 6.4: Adaptive Recommendation Engine
-- Additive only, ships DARK. NOT applied by this work. Read-only from JobMatchingService's
-- perspective in this pass — see LEARNING.md-equivalent deliverable notes: not wired into the
-- live scoring path yet, so this table has no effect on today's recommendations.

CREATE TABLE IF NOT EXISTS recommendation_weight (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    dimension     VARCHAR(24) NOT NULL, -- COMPANY|ROLE|SKILL|INDUSTRY|LOCATION|SALARY
    dimension_key VARCHAR(255) NOT NULL,
    boost         INT NOT NULL DEFAULT 0, -- signed: positive boost, negative penalty
    reason        TEXT,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, dimension, dimension_key)
);
CREATE INDEX IF NOT EXISTS idx_recommendation_weight_user ON recommendation_weight(user_id, dimension);
