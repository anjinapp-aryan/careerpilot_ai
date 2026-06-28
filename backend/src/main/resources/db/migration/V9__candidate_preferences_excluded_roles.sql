-- CareerPilot AI — Excluded Roles (Phase 1 completion).
-- Additive only: one new nullable column on the existing candidate_preferences table, so
-- Hibernate's `validate` stays green and every existing flow (preferences CRUD, recommendation
-- scoring, profile snapshot) is untouched. Idempotent so it can be hand-applied against the
-- managed Neon instance (which baselines rather than auto-migrating) — same convention as V4–V8.
--
-- excluded_roles holds the comma-joined role/family names a user never wants to see in
-- recommendations (e.g. "Sales,Marketing,Support"), mirroring how preferred_roles is stored.
-- NULL/empty = no exclusions = zero behavior change, so deploying this column is inert until a
-- user actually sets a value AND the matching filter reads it.

ALTER TABLE candidate_preferences
    ADD COLUMN IF NOT EXISTS excluded_roles TEXT;
