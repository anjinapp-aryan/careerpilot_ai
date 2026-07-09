-- CareerPilot AI — Phase 5: Daily Job Discovery Agent
-- Additive only, ships DARK. NOT applied by this work (see CLAUDE.md V-migration convention).
--
-- daily_discovery_run: one row per scheduled/manual run of the whole pipeline.
-- daily_discovery_analytics: one aggregate row per run (distributions stored as JSON text,
--   consistent with the rest of this codebase's convention of text/JSON columns over jsonb).
-- daily_career_summary: one row per (run, user) — the AI-generated daily briefing text.

CREATE TABLE IF NOT EXISTS daily_discovery_run (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id   UUID,
    status           VARCHAR(24) NOT NULL DEFAULT 'RUNNING', -- RUNNING|SUCCESS|PARTIAL|FAILED
    jobs_fetched     INT,
    jobs_normalized  INT,
    jobs_deduped     INT,
    users_processed  INT,
    error_message    TEXT,
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_daily_discovery_run_started ON daily_discovery_run(started_at DESC);

CREATE TABLE IF NOT EXISTS daily_discovery_analytics (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                      UUID NOT NULL REFERENCES daily_discovery_run(id),
    user_id                     UUID, -- NULL = pipeline-wide aggregate row
    domestic_jobs               INT,
    international_jobs          INT,
    recommended_jobs            INT,
    must_apply_jobs             INT,
    high_priority_jobs          INT,
    human_review_jobs           INT,
    hidden_jobs                 INT,
    average_score               NUMERIC(6,2),
    industry_distribution       TEXT, -- JSON {"TECH": 12, ...}
    skill_distribution          TEXT, -- JSON {"java": 8, ...}
    company_distribution        TEXT, -- JSON {"Google": 2, ...}
    match_strength_distribution TEXT, -- JSON {"EXCELLENT": 3, ...}
    computed_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_daily_discovery_analytics_run ON daily_discovery_analytics(run_id);
CREATE INDEX IF NOT EXISTS idx_daily_discovery_analytics_user ON daily_discovery_analytics(user_id, computed_at DESC);

CREATE TABLE IF NOT EXISTS daily_career_summary (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id                      UUID NOT NULL REFERENCES daily_discovery_run(id),
    user_id                     UUID NOT NULL,
    summary_text                TEXT,
    jobs_fetched                INT,
    jobs_deduped                INT,
    recommended_count           INT,
    must_apply_count            INT,
    high_priority_count         INT,
    top_companies               TEXT, -- comma-joined
    top_skills                  TEXT, -- comma-joined
    interview_probability_delta NUMERIC(6,2),
    offer_probability_delta     NUMERIC(6,2),
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_daily_career_summary_user ON daily_career_summary(user_id, created_at DESC);
