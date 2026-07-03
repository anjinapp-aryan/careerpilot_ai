-- CareerPilot AI — Phase 2B-1: action category on persisted recommendations (Step 7)
-- Additive only. Buckets a recommendation by its already-computed match score into an action
-- category (AUTO_APPLY >=95, HUMAN_REVIEW 85-94, GOOD_MATCH 70-84, else IGNORE). Nullable: rows
-- written while JOB_AUTO_CATEGORIZATION_ENABLED=false (the default) leave this NULL, so existing
-- behavior is unchanged. The matcher never re-scores to produce this — it is a pure function of
-- the existing match_score.
--
-- NOTE: same Neon hand-apply convention as V4–V17 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS category VARCHAR(20);
