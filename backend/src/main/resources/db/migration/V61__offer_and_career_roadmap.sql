-- CareerPilot AI — Gap B (Offer Intelligence & Salary Negotiation) + Gap C (Career Coach
-- roadmap persistence). Additive only, ships DARK (offer.intelligence.enabled=false,
-- career.roadmap.persistence.enabled=false). Not applied by this work — hand-applied to Neon
-- later, same convention as V50-V60 (see CLAUDE.md's baseline-on-migrate note).
--
-- No DB-level FK constraints for cross-aggregate references (job_id) — same convention as the
-- rest of this schema (e.g. applications.job_id, application_submission_session.job_id in V51/V57).

CREATE TABLE IF NOT EXISTS offers (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL,
    job_id                 UUID,
    company_name           VARCHAR(255),
    base_salary            NUMERIC(14, 2),
    bonus                  NUMERIC(14, 2),
    rsu_value              NUMERIC(14, 2),
    equity_description     TEXT,
    benefits_summary       TEXT,
    joining_bonus          NUMERIC(14, 2),
    currency               VARCHAR(8),
    source                 VARCHAR(32) NOT NULL DEFAULT 'MANUAL', -- MANUAL | SALARY_INTELLIGENCE_AGENT
    market_p25             NUMERIC(14, 2),
    market_p50             NUMERIC(14, 2),
    market_p75             NUMERIC(14, 2),
    market_p90             NUMERIC(14, 2),
    negotiation_strategy   TEXT,
    leverage_points        TEXT, -- JSON array, stored as text (no jsonb dependency elsewhere in this table)
    -- Dedup/idempotency key for agent-sourced offers: the LangGraph thread_id that produced this
    -- row, so repeated WorkflowService transitions on the same run upsert instead of duplicating.
    -- Null for MANUAL offers.
    source_thread_id       VARCHAR(128),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_offers_user_id ON offers(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_offers_source_thread_id ON offers(source_thread_id);

-- Gap C — additive columns on the existing single-row-per-user career_strategy table (V49),
-- carrying the LangGraph career_strategy agent's horizoned roadmap + skill gaps once persisted.
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS roadmap_3_month TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS roadmap_6_month TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS roadmap_12_month TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS skill_gaps_json TEXT;
