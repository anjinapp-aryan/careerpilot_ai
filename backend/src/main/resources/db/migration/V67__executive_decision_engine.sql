-- Phase 7.19.5 — extends the EXISTING daily_career_summary row with the Executive Decision
-- Engine's output rather than creating a rival table/pipeline. Not applied by this work —
-- hand-apply to Neon, same convention as V33/V62/V64/V65/V66.

ALTER TABLE daily_career_summary ADD COLUMN IF NOT EXISTS executive_decisions_json TEXT;
ALTER TABLE daily_career_summary ADD COLUMN IF NOT EXISTS career_health_score NUMERIC(5,2);
