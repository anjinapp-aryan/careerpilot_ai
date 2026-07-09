-- CareerPilot AI — Phase 2E.7: Application Tracking Engine
-- HIGH RISK PHASE. Additive only, ships DARK. NOT applied by this work.
--
-- application_tracking is an APPEND-ONLY status timeline for a submitted application, SEPARATE from
-- the existing org-scoped `applications` kanban table (which is never overwritten — it is linked via
-- application_id). States: DRAFT|SUBMITTED|VIEWED|UNDER_REVIEW|INTERVIEW|ASSESSMENT|REJECTED|OFFER|HIRED.
--
-- NOTE: same Neon hand-apply convention as V4-V33 (idempotent DDL).

CREATE TABLE IF NOT EXISTS application_tracking (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_execution_id UUID REFERENCES application_execution(id) ON DELETE CASCADE,
    user_id                  UUID NOT NULL,
    job_id                   UUID NOT NULL,
    application_id           UUID,   -- links to applications(id); tracking never mutates that row
    status                   VARCHAR(20) NOT NULL, -- DRAFT|SUBMITTED|VIEWED|UNDER_REVIEW|INTERVIEW|ASSESSMENT|REJECTED|OFFER|HIRED
    note                     TEXT,
    changed_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_tracking_user_job
    ON application_tracking(user_id, job_id, changed_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_tracking_execution
    ON application_tracking(application_execution_id, changed_at DESC);
