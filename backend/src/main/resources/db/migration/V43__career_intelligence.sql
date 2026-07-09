-- CareerPilot AI — Phase 3A.6: Career Intelligence Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- career_intelligence holds append-only per-user learned probabilities per dimension (overall career
-- success, interview/offer probability, and per country/company/technology/role success). Computed
-- deterministically from application_analytics snapshots (no LLM).

CREATE TABLE IF NOT EXISTS career_intelligence (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    dimension     VARCHAR(48) NOT NULL,   -- CAREER_SUCCESS|INTERVIEW_PROBABILITY|OFFER_PROBABILITY|COUNTRY_SUCCESS|COMPANY_SUCCESS|TECHNOLOGY_SUCCESS|ROLE_SUCCESS
    dimension_key VARCHAR(128),
    probability   NUMERIC(6,4),
    sample_size   INT,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_career_intelligence_user_dimension
    ON career_intelligence(user_id, dimension, computed_at DESC);
