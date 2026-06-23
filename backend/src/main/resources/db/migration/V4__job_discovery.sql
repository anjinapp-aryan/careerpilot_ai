-- CareerPilot AI — Phase 2 Job Discovery (Stage 1)
-- Additive only: extends the existing `jobs` table with discovery metadata and adds two
-- new tables. Existing columns/queries are untouched, so org-scoped Browse/Add-job/Apply
-- flows do not regress. Discovered jobs are stored as a GLOBAL pool (org_id IS NULL,
-- source = provider name) and de-duplicated on (source, external_id).
--
-- NOTE: on the managed Neon instance Flyway baselines and does not auto-apply migrations
-- (see CLAUDE.md / V3 precedent). This DDL is written idempotently so it can be applied by
-- hand against DATABASE_URL_PY and also runs cleanly on a fresh DB.

-- ── Discovery metadata on the shared jobs table (all nullable) ──────────────────
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS external_id  VARCHAR(255);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS country      VARCHAR(80);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS city         VARCHAR(120);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS salary_min   NUMERIC;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS salary_max   NUMERIC;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS currency     VARCHAR(10);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS remote       BOOLEAN;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS skills       TEXT;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS source_url   VARCHAR(1000);
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS posted_date  TIMESTAMPTZ;

-- Dedup key for ingested jobs; manual jobs have NULL external_id and are unaffected.
CREATE UNIQUE INDEX IF NOT EXISTS idx_jobs_source_external
    ON jobs(source, external_id) WHERE external_id IS NOT NULL;

-- Fast reads for the Domestic/International tabs over the global discovered pool.
CREATE INDEX IF NOT EXISTS idx_jobs_discovered
    ON jobs(country, posted_date DESC) WHERE org_id IS NULL;

-- ── Persisted per-user recommendations (rule-based matcher output) ──────────────
CREATE TABLE IF NOT EXISTS job_recommendations (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resume_id              UUID REFERENCES resumes(id) ON DELETE SET NULL,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    match_score            INT NOT NULL DEFAULT 0,
    matching_skills        TEXT,
    missing_skills         TEXT,
    recommendation_reason  TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_recommendations_user_job UNIQUE (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_job_recommendations_user_score
    ON job_recommendations(user_id, match_score DESC);

-- ── Audit of each provider fetch (one row per provider per run) ─────────────────
CREATE TABLE IF NOT EXISTS job_fetch_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider        VARCHAR(60) NOT NULL,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    jobs_fetched    INT NOT NULL DEFAULT 0,
    jobs_persisted  INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'RUNNING', -- RUNNING|SUCCESS|FAILED
    error_message   TEXT
);
CREATE INDEX IF NOT EXISTS idx_job_fetch_audit_provider ON job_fetch_audit(provider, started_at DESC);
