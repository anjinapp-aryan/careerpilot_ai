-- CareerPilot AI — Phase 2B-4: job_ai_enrichment field completion
-- Additive only. Adds the 6 signal categories the Phase 2B spec asks for that V12's original
-- schema didn't capture: role family, work mode, visa support, inferred country, company type,
-- company size, plus years of experience. All nullable, all populated by
-- JobAiEnrichmentExtractor on the next enrichment pass — existing rows simply have these columns
-- NULL until re-enriched (enrichment_version bump lets a future pass target them specifically).
--
-- Still sourced from the `jobs` table (not the Phase 2A lake) — see job_ai_enrichment.job_id's FK.
-- Consuming the lake's deduped/filtered pool for enrichment is a separate, larger architectural
-- change (promotion into `jobs`, or a parallel lake-only enrichment path) deliberately deferred.
--
-- NOTE: same Neon hand-apply convention as V4–V18 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS role_family VARCHAR(60);
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS work_mode VARCHAR(10);        -- REMOTE|HYBRID|ONSITE
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS visa_support BOOLEAN;
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS country VARCHAR(80);
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS company_type VARCHAR(40);     -- STARTUP|SCALEUP|ENTERPRISE|AGENCY|...
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS company_size VARCHAR(20);     -- STARTUP|SMB|MID|ENTERPRISE
ALTER TABLE job_ai_enrichment ADD COLUMN IF NOT EXISTS experience_years INT;
