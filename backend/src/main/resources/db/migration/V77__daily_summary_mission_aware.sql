-- Phase 6A — Mission-Aware Daily Coach Integration. Extends the EXISTING daily_career_summary
-- table (Phase 5) with nullable columns only — no new table, no new Daily Coach. Populated only
-- when career.mission.daily.enabled=true AND the user has an active Mission; null otherwise,
-- same dark-ship convention as every other additive column in this table (see the existing
-- Phase 7.19.5 Executive Decision Engine columns immediately above these for precedent).
ALTER TABLE daily_career_summary
    ADD COLUMN mission_id UUID REFERENCES career_mission(id) ON DELETE SET NULL,
    ADD COLUMN mission_name TEXT,
    ADD COLUMN mission_progress_percent INTEGER,
    ADD COLUMN current_strategy_country VARCHAR(100),
    ADD COLUMN alternative_strategy_country VARCHAR(100),
    ADD COLUMN todays_mission_tasks JSONB,
    ADD COLUMN priority_workflows JSONB,
    ADD COLUMN high_risk_areas JSONB,
    ADD COLUMN recommended_learning JSONB,
    ADD COLUMN recommended_jobs JSONB,
    ADD COLUMN recommended_interviews JSONB,
    ADD COLUMN estimated_completion_timeline VARCHAR(200),
    ADD COLUMN mission_recommendation TEXT;
