-- CareerPilot AI — Phase 2 Increment B: LLM Job Enrichment
-- Additive only. A 1:1 companion table to `jobs` holding *semantic* enrichment that the cheap
-- keyword tier (JobEnricher) cannot derive: normalized seniority, canonical skills, industry
-- domains, an estimated salary band, and a short summary. Kept OUT of the `jobs` table on purpose
-- so the daily JobNormalizer.merge() re-upsert can never wipe it (merge touches `jobs` columns only),
-- and so enrichment metadata (model, version, confidence, fingerprint) stays separate from the listing.
--
-- Produced-but-not-consumed in v1: matching/recommendations do NOT read this yet (Increment D will).
-- Gated DARK by jobs.enrich.ai.enabled=false → no rows are ever written until explicitly flipped on.
--
-- NOTE: same Neon hand-apply convention as V4–V11 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS job_ai_enrichment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    seniority_level     VARCHAR(40),                 -- Junior|Mid|Senior|Lead|Staff|Principal|Manager|Director
    normalized_skills   JSONB,                       -- canonical skill names (deduped)
    domains             JSONB,                       -- industry domains, e.g. "Fintech","Healthcare"
    employment_type     VARCHAR(40),                 -- Full-time|Part-time|Contract|Internship|Temporary
    salary_band_min     NUMERIC,
    salary_band_max     NUMERIC,
    salary_currency     VARCHAR(10),
    salary_estimated    BOOLEAN,                     -- true = inferred by the model; false = from the posting
    summary             TEXT,                        -- 1-2 sentence neutral role summary
    confidence_score    NUMERIC,                     -- 0.0-1.0 model self-confidence
    model               VARCHAR(80),                 -- provider/model that produced this row (attribution)
    enrichment_version  INT NOT NULL DEFAULT 1,      -- schema/prompt version, for future re-enrichment
    content_fingerprint VARCHAR(80),                 -- SHA-256 of title+description at enrichment time
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_ai_enrichment_job UNIQUE (job_id)
);

-- The work-list query LEFT JOINs jobs → this table on job_id and filters e.id IS NULL.
CREATE INDEX IF NOT EXISTS idx_job_ai_enrichment_job ON job_ai_enrichment(job_id);
-- Recency reads for the future admin/stats surface.
CREATE INDEX IF NOT EXISTS idx_job_ai_enrichment_updated ON job_ai_enrichment(updated_at DESC);
