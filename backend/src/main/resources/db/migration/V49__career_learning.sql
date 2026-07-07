-- CareerPilot AI — Phase 6.6: Adaptive Career Intelligence Engine
-- Additive only, ships DARK. NOT applied by this work. Reads the existing career_intelligence
-- table (Phase 3A.6) as an input signal only — never writes to it.

CREATE TABLE IF NOT EXISTS career_learning (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    dimension     VARCHAR(24) NOT NULL, -- COMPANY|SKILL|INDUSTRY|LOCATION|SALARY
    dimension_key VARCHAR(255) NOT NULL,
    score         NUMERIC(6,4),
    sample_size   INT,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, dimension, dimension_key)
);
CREATE INDEX IF NOT EXISTS idx_career_learning_user_dim ON career_learning(user_id, dimension, score DESC);

CREATE TABLE IF NOT EXISTS career_strategy (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL UNIQUE,
    career_success_probability NUMERIC(6,4),
    interview_probability      NUMERIC(6,4),
    offer_probability          NUMERIC(6,4),
    career_growth_probability  NUMERIC(6,4),
    market_demand_score        NUMERIC(6,4),
    recommended_trajectory     TEXT,
    computed_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
