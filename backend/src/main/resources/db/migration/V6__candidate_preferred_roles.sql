-- CareerPilot AI — add preferred roles to candidate preferences.
-- Additive, nullable. Idempotent for hand-application against the managed Neon instance
-- (which baselines rather than auto-migrating — same convention as V4/V5).

ALTER TABLE candidate_preferences ADD COLUMN IF NOT EXISTS preferred_roles TEXT; -- comma-joined
