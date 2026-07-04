-- CareerPilot AI — Phase 3A.5: Application Analytics Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- application_analytics holds append-only per-user outcome metric snapshots (one row per metric per
-- recompute). Deliberately separate from Phase 2E's execution_analytics (different bounded context).
-- Metrics: APPLICATION_COUNT, INTERVIEW_RATE, OFFER_RATE, REJECTION_RATE, COUNTRY_SUCCESS,
-- COMPANY_SUCCESS, TECHNOLOGY_SUCCESS, SALARY_SUCCESS, DOMAIN_SUCCESS.

CREATE TABLE IF NOT EXISTS application_analytics (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    metric        VARCHAR(48) NOT NULL,
    value         NUMERIC(12,4),
    dimension_key VARCHAR(128),
    window_start  TIMESTAMPTZ,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_analytics_user_metric
    ON application_analytics(user_id, metric, computed_at DESC);
