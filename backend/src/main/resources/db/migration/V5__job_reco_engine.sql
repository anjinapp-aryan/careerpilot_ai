-- CareerPilot AI — Enterprise Job Recommendation Engine
-- Additive only. Every column is nullable / defaulted and every table is new, so
-- Hibernate's `validate` stays green and the existing org-scoped + discovery flows
-- do not regress. Mirrors the V4 convention: idempotent so it can be hand-applied
-- against the managed Neon instance (which baselines rather than auto-migrating).

-- ── Enrichment on the shared jobs table (nullable; populated for discovered jobs) ──
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS remote_type           VARCHAR(10);   -- REMOTE|HYBRID|ONSITE
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS sponsorship_available BOOLEAN;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS relocation_support    BOOLEAN;
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS company_size          VARCHAR(20);   -- STARTUP|SMB|MID|ENTERPRISE
ALTER TABLE jobs ADD COLUMN IF NOT EXISTS required_experience   INT;           -- parsed "5+ years"

-- Facet filters for the Recommended / International tabs over the global pool.
CREATE INDEX IF NOT EXISTS idx_jobs_facets
    ON jobs(remote_type, sponsorship_available, relocation_support) WHERE org_id IS NULL;
CREATE INDEX IF NOT EXISTS idx_jobs_posted_recent
    ON jobs(posted_date DESC) WHERE org_id IS NULL;

-- ── Persistent candidate preferences (one row per user) ──────────────────────────
CREATE TABLE IF NOT EXISTS candidate_preferences (
    user_id                    UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    preferred_countries        TEXT,           -- comma-joined, mirrors jobs.skills style
    preferred_cities           TEXT,
    remote_preference          BOOLEAN NOT NULL DEFAULT FALSE,
    hybrid_preference          BOOLEAN NOT NULL DEFAULT FALSE,
    onsite_preference          BOOLEAN NOT NULL DEFAULT FALSE,
    visa_sponsorship_required  BOOLEAN NOT NULL DEFAULT FALSE,
    relocation_required        BOOLEAN NOT NULL DEFAULT FALSE,
    salary_expectation_min     NUMERIC,
    salary_expectation_max     NUMERIC,
    salary_currency            VARCHAR(10),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Extend persisted recommendations with breakdown + confidence (additive) ───────
ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS score_breakdown  TEXT;     -- JSON of 6 factors
ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS confidence_level VARCHAR(10); -- HIGH|MEDIUM|LOW

-- ── LLM explanation cache (one row per user+job; avoids re-calling the LLM) ───────
CREATE TABLE IF NOT EXISTS job_recommendation_explanation (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    matching_skills     TEXT,
    missing_skills      TEXT,
    resume_improvements TEXT,
    ats_improvements    TEXT,
    model_used          VARCHAR(60),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_explanation_user_job UNIQUE (user_id, job_id)
);
