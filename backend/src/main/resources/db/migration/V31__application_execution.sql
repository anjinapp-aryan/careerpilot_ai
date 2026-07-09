-- CareerPilot AI — Phase 2E.1: Application Execution Engine
-- HIGH RISK PHASE. Additive only, ships fully DARK. Nothing in this migration is applied in the
-- 2E build; it is written for a later, separate, human-gated enablement. No code path submits an
-- application: with all 2E flags at their defaults the pipeline halts at approval PENDING.
--
-- application_execution is the state machine for one attempt to execute (submit) an assembled
-- application package. States: QUEUED | VALIDATING | EXECUTING | SUBMITTED | FAILED | RETRY | ABORTED.
-- Append-only audit trail in application_execution_audit.
--
-- NOTE: same Neon hand-apply convention as V4-V30 (idempotent DDL). NOT applied by this work.

CREATE TABLE IF NOT EXISTS application_execution (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    application_package_id UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    application_id         UUID,                 -- links to applications(id) once tracked
    provider               VARCHAR(60),          -- resolved ATS/browser provider name, null until known
    execution_status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED', -- QUEUED|VALIDATING|EXECUTING|SUBMITTED|FAILED|RETRY|ABORTED
    execution_type         VARCHAR(20) NOT NULL, -- BROWSER | ATS_CONNECTOR | MANUAL
    attempt_count          INTEGER NOT NULL DEFAULT 0,
    failure_reason         TEXT,
    started_at             TIMESTAMPTZ,
    completed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_execution_user_job
    ON application_execution(user_id, job_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_execution_status
    ON application_execution(execution_status, created_at);

CREATE TABLE IF NOT EXISTS application_execution_audit (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL,
    job_id                 UUID NOT NULL,
    application_execution_id UUID,
    outcome                VARCHAR(24) NOT NULL, -- QUEUED|VALIDATING|EXECUTING|SUBMITTED|FAILED|RETRY|ABORTED|ERROR
    reason                 TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_execution_audit_user
    ON application_execution_audit(user_id, created_at DESC);
