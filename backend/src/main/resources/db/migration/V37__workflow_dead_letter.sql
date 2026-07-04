-- CareerPilot AI — Phase 3A.0: Workflow Dead Letter table
-- Additive only, ships DARK. NOT applied by this work.
--
-- Every Phase 3A worker's catch block writes one of these instead of propagating, so a failing stage
-- is isolated, auditable, and replayable by a human. Append-only. Joins to workflow_correlation on
-- correlation_id (nullable — a failure before a correlation is minted still gets captured).

CREATE TABLE IF NOT EXISTS workflow_dead_letter (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id UUID,
    workflow       VARCHAR(64) NOT NULL,
    stage          VARCHAR(64) NOT NULL,
    payload        TEXT,
    exception      TEXT,
    retry_count    INT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_workflow_dead_letter_correlation
    ON workflow_dead_letter(correlation_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_dead_letter_workflow
    ON workflow_dead_letter(workflow, created_at DESC);
