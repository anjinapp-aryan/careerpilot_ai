-- CareerPilot AI — Home Country (Domestic vs International scope separation).
-- Additive only: two nullable columns, no change to existing data or constraints, so Hibernate's
-- `validate` stays green and every existing flow (preferences CRUD, profile snapshot, matching,
-- discovery) keeps working untouched. Idempotent so it can be hand-applied against the managed Neon
-- instance (which baselines rather than auto-migrating) — same convention as V4–V9.
--
-- `home_country` is the single source of truth for the Domestic tab: discovery for "Domestic" is
-- derived from the candidate's own home country, NOT from any client-supplied query param. The
-- editable value lives in candidate_preferences; candidate_profiles carries a snapshot (mirroring
-- the other preference columns there) so the canonical profile stays self-contained.

ALTER TABLE candidate_preferences ADD COLUMN IF NOT EXISTS home_country VARCHAR(80);
ALTER TABLE candidate_profiles    ADD COLUMN IF NOT EXISTS home_country VARCHAR(80);
