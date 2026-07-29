-- Mission Orchestrator, Phase 5. A deterministic decision engine reading Mission + Strategy
-- state and recommending which Workflow Registry entries (Phase 4) to run next — it does NOT
-- execute anything itself (see MissionOrchestratorService javadoc). mission_execution is one row
-- per orchestrator run; workflow_decision_log is the ranked list of recommendations from that run.
CREATE TABLE mission_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id UUID NOT NULL REFERENCES career_mission(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    decision_summary TEXT,
    resume_score_at_run INTEGER,
    ran_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_mission_execution_mission ON mission_execution (mission_id, ran_at DESC);

CREATE TABLE workflow_decision_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_execution_id UUID NOT NULL REFERENCES mission_execution(id) ON DELETE CASCADE,
    workflow_id VARCHAR(100) NOT NULL,
    reason TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_workflow_decision_log_execution ON workflow_decision_log (mission_execution_id);
