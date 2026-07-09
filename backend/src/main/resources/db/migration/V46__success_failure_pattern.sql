-- CareerPilot AI — Phase 6.2/6.3: Success & Failure Pattern Engines
-- Additive only, ships DARK. NOT applied by this work.

CREATE TABLE IF NOT EXISTS success_pattern (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID NOT NULL,
    dimension      VARCHAR(24) NOT NULL, -- COMPANY|ROLE|SKILL|RESUME|LOCATION|INDUSTRY|SALARY
    dimension_key  VARCHAR(255) NOT NULL,
    applications   INT NOT NULL DEFAULT 0,
    interviews     INT NOT NULL DEFAULT 0,
    offers         INT NOT NULL DEFAULT 0,
    success_rate   NUMERIC(6,4),
    computed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, dimension, dimension_key)
);
CREATE INDEX IF NOT EXISTS idx_success_pattern_user_dim ON success_pattern(user_id, dimension, success_rate DESC);

CREATE TABLE IF NOT EXISTS failure_pattern (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL,
    dimension           VARCHAR(24) NOT NULL,
    dimension_key       VARCHAR(255) NOT NULL,
    applications        INT NOT NULL DEFAULT 0,
    responses           INT NOT NULL DEFAULT 0,
    failure_rate        NUMERIC(6,4),
    recommended_penalty INT,
    computed_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, dimension, dimension_key)
);
CREATE INDEX IF NOT EXISTS idx_failure_pattern_user_dim ON failure_pattern(user_id, dimension, failure_rate DESC);
