-- CareerPilot AI — Phase 3A.0: Global Workflow Correlation Framework
-- Additive only, ships DARK. NOT applied by this work (same Neon hand-apply convention as V4-V35).
--
-- workflow_correlation holds one row per running Phase 3A workflow instance, keyed by correlation_id,
-- upserted on every stage transition so the whole Applied -> Offer path is traceable end-to-end.

CREATE TABLE IF NOT EXISTS workflow_correlation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id UUID NOT NULL UNIQUE,
    user_id        UUID NOT NULL,
    job_id         UUID,
    application_id UUID,
    workflow_type  VARCHAR(64) NOT NULL,
    workflow_stage VARCHAR(64) NOT NULL,
    status         VARCHAR(32) NOT NULL,       -- STARTED|IN_PROGRESS|COMPLETED|FAILED|DEAD_LETTERED
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_workflow_correlation_user
    ON workflow_correlation(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_correlation_status
    ON workflow_correlation(status);
