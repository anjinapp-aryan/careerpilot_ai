-- CareerPilot AI — Phase 2E.4: Human Approval Workflow
-- HIGH RISK PHASE. Additive only, ships DARK. NOT applied by this work.
--
-- approval_queue parks an application-execution request for a MANDATORY human decision. In v1 there
-- is no auto-approve: an APPROVED transition happens only via the human POST /api/execution/approve
-- endpoint, which is the sole publisher of ApprovalGrantedEvent. States:
--   PENDING | APPROVED | REJECTED | EXPIRED
-- approval_audit is the append-only decision trail.
--
-- NOTE: same Neon hand-apply convention as V4-V31 (idempotent DDL).

CREATE TABLE IF NOT EXISTS approval_queue (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    application_package_id UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    safety_verdict         VARCHAR(12) NOT NULL,  -- SAFE | REVIEW (BLOCK never enqueues)
    status                 VARCHAR(12) NOT NULL DEFAULT 'PENDING', -- PENDING|APPROVED|REJECTED|EXPIRED
    decided_by             VARCHAR(120),
    decision_note          TEXT,
    requested_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at             TIMESTAMPTZ,
    expires_at             TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_approval_queue_user_status
    ON approval_queue(user_id, status, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_approval_queue_status
    ON approval_queue(status, requested_at);

CREATE TABLE IF NOT EXISTS approval_audit (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,
    job_id            UUID NOT NULL,
    approval_id       UUID,
    outcome           VARCHAR(24) NOT NULL, -- ENQUEUED|APPROVED|REJECTED|EXPIRED|ERROR
    reason            TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_approval_audit_user
    ON approval_audit(user_id, created_at DESC);
