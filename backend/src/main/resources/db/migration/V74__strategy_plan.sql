-- Strategy Engine, Phase 3. Converts a Mission into an actionable plan. Deliberately does NOT
-- touch career_strategy (Phase 6.6's single-row-per-user computed AI/probability snapshot — a
-- fundamentally different shape: narrative text blobs, not discrete completable actions).
-- Reuses the existing career_goal table (Mission Engine, Phase 1) as the action-item
-- representation instead of a new, near-duplicate strategy_action table — a goal already has
-- exactly the shape an action needs (title, status ACTIVE/PAUSED/COMPLETED). strategy_plan is the
-- new grouping/timeframe concept; each POST /api/strategy/generate call inserts a NEW row rather
-- than updating one in place, so this table is itself the plan history — no separate
-- strategy_history table.
CREATE TABLE strategy_plan (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id UUID NOT NULL REFERENCES career_mission(id) ON DELETE CASCADE,
    timeframe_days INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_strategy_plan_mission ON strategy_plan (mission_id, generated_at DESC);

ALTER TABLE career_goal ADD COLUMN strategy_plan_id UUID REFERENCES strategy_plan(id) ON DELETE CASCADE;
CREATE INDEX idx_career_goal_strategy_plan ON career_goal (strategy_plan_id);
