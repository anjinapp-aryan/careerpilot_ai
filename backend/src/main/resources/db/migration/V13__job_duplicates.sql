-- CareerPilot AI — Phase 2 Increment C: Fuzzy Job Deduplication
-- Additive only. One row per job that has been duplicate-checked (whether or not it turned out to
-- be a duplicate of anything) — self-referencing canonical_job_id marks a job as the canonical
-- member of its own cluster. Kept OUT of the `jobs` table for the same reason as
-- job_ai_enrichment: the daily JobNormalizer.merge() re-upsert never touches this table.
--
-- Produced-but-not-consumed in v1: Browse/Recommended/Discovered listings do NOT filter out
-- non-canonical duplicates yet — this only detects and exposes clusters via the Admin Dashboard.
-- Gated DARK by jobs.dedup.enabled=false → no rows are ever written until explicitly flipped on.
--
-- NOTE: same Neon hand-apply convention as V4–V12 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

CREATE TABLE IF NOT EXISTS job_duplicates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id              UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    canonical_job_id    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    duplicate_group_id  UUID NOT NULL,
    similarity_score    NUMERIC,             -- 0.0-1.0; 1.0 for the canonical's own self-row
    match_signals       TEXT,                -- e.g. "embeddingSim=0.96,titleJaccard=0.83,companyMatch=true"
    detected_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_duplicates_job UNIQUE (job_id)
);

CREATE INDEX IF NOT EXISTS idx_job_duplicates_group ON job_duplicates(duplicate_group_id);
CREATE INDEX IF NOT EXISTS idx_job_duplicates_canonical ON job_duplicates(canonical_job_id);
