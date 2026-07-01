-- CareerPilot AI — Phase 2A fix: composite index on job_normalized(country, city)
-- The dedup service calls findDedupCandidates(country, city), which returns all rows
-- for a location bucket. Without this index that's a full-table scan — O(N²) at scale.
-- Idempotent (IF NOT EXISTS). Apply to Neon before enabling JOB_DISCOVERY_DEDUP_ENABLED=true
-- in production.
CREATE INDEX IF NOT EXISTS idx_job_normalized_country_city ON job_normalized(country, city);
