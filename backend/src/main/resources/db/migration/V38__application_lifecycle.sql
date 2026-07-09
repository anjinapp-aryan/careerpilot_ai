-- CareerPilot AI — Phase 3A.1: Application Lifecycle Engine
-- Additive only, ships DARK. NOT applied by this work (same Neon hand-apply convention as V4-V37).
--
-- application_lifecycle is the canonical per-(user,job) lifecycle row (the "Career OS" tracker),
-- deliberately named to avoid colliding with Phase 2E's `application_tracking` (V34) execution
-- timeline. Status changes are validated by ApplicationStatusMachine and recorded append-only in
-- application_status_history + application_lifecycle_audit. States:
--   DRAFT|SUBMITTED|VIEWED|UNDER_REVIEW|ASSESSMENT|TECHNICAL_INTERVIEW|MANAGER_INTERVIEW|
--   SYSTEM_DESIGN|HR_INTERVIEW|FINAL_ROUND|OFFER_RECEIVED|NEGOTIATION|ACCEPTED|REJECTED|WITHDRAWN|EXPIRED

CREATE TABLE IF NOT EXISTS application_lifecycle (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    job_id           UUID NOT NULL,
    application_id   UUID,
    company          VARCHAR(255),
    country          VARCHAR(128),
    application_date TIMESTAMPTZ,
    current_status   VARCHAR(32) NOT NULL,
    previous_status  VARCHAR(32),
    source           VARCHAR(64),
    metadata         TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_lifecycle_user_job UNIQUE (user_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_application_lifecycle_user
    ON application_lifecycle(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_application_lifecycle_status
    ON application_lifecycle(current_status);

CREATE TABLE IF NOT EXISTS application_status_history (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_id UUID NOT NULL REFERENCES application_lifecycle(id) ON DELETE CASCADE,
    from_status  VARCHAR(32),
    to_status    VARCHAR(32) NOT NULL,
    note         VARCHAR(512),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_status_history_lifecycle
    ON application_status_history(lifecycle_id, changed_at DESC);

CREATE TABLE IF NOT EXISTS application_lifecycle_event (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_id UUID NOT NULL REFERENCES application_lifecycle(id) ON DELETE CASCADE,
    event_type   VARCHAR(64) NOT NULL,
    event_source VARCHAR(64),
    details      TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_lifecycle_event_lifecycle
    ON application_lifecycle_event(lifecycle_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS application_lifecycle_audit (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lifecycle_id UUID NOT NULL REFERENCES application_lifecycle(id) ON DELETE CASCADE,
    action       VARCHAR(32) NOT NULL,
    actor        VARCHAR(128),
    detail       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_lifecycle_audit_lifecycle
    ON application_lifecycle_audit(lifecycle_id, created_at DESC);
