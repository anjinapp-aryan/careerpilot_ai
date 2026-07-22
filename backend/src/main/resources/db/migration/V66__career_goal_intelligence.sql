-- Phase 7.19 — extends the EXISTING career_strategy row (Phase 6.6 / Gap C) rather than creating a
-- rival table. Not applied by this work — hand-apply to Neon, same convention as V33/V62/V64/V65.

ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS skill_gap_intelligence_json TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS promotion_readiness_json TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS career_goal_json TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS computed_roadmap_json TEXT;
ALTER TABLE career_strategy ADD COLUMN IF NOT EXISTS career_goal_computed_at TIMESTAMPTZ;
