-- CareerPilot AI — Phase 1.5: Candidate Profile model completion
-- Additive only. Adds 6 signal categories that the original V8 candidate_profiles table did
-- not capture: technologies, certifications, industries, leadership experience, cloud expertise,
-- and career goals. All nullable, all populated by CandidateProfileExtractor on the next
-- resume upload/optimize/rebuild — existing rows simply have these columns NULL until then.
--
-- No flag gates this migration: it only widens the schema and the extraction prompt's output
-- scope, exactly the same risk class as the fields already in V8. Existing consumers of
-- CandidateProfile are unaffected (additive columns, no renamed/removed columns).
--
-- NOTE: same Neon hand-apply convention as V4–V13 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS technologies JSONB;
ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS certifications JSONB;
ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS industries JSONB;
ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS leadership_experience BOOLEAN;
ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS cloud_expertise BOOLEAN;
ALTER TABLE candidate_profiles ADD COLUMN IF NOT EXISTS career_goals JSONB;
