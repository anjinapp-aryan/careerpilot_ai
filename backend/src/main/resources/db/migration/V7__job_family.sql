-- CareerPilot AI — Job family / industry classification for the recommendation quality filter.
-- Additive only: one nullable column on the shared jobs table + a partial index over the
-- discovered pool. Hibernate `validate` stays green; existing org-scoped + discovery flows
-- do not regress. Mirrors the V4–V6 convention: idempotent so it can be hand-applied against
-- the managed Neon instance (which baselines rather than auto-migrating).
--
-- Values written by JobEnricher at ingest (keyword-based, no LLM):
--   TECH | MARKETING | SALES | HR | RECRUITER | SUPPORT | FINANCE | OTHER
-- NULL = unclassified (treated as not-excluded so nothing silently disappears).

ALTER TABLE jobs ADD COLUMN IF NOT EXISTS job_family VARCHAR(40);

-- Fast SQL-level exclusion of non-technical families over the global discovered pool.
CREATE INDEX IF NOT EXISTS idx_jobs_family
    ON jobs(job_family) WHERE org_id IS NULL;
