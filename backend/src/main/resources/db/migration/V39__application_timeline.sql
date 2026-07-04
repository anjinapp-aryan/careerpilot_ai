-- CareerPilot AI — Phase 3A.2: Timeline Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- application_timeline is an append-only, human-readable event stream for an application
-- (Applied -> Viewed -> Assessment -> Technical -> Offer). Distinct from application_status_history:
-- history is validated status transitions; the timeline records observable events with a confidence
-- (some are inferred, e.g. from email intelligence).

CREATE TABLE IF NOT EXISTS application_timeline (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL,
    job_id       UUID NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    event_source VARCHAR(64),
    confidence   DOUBLE PRECISION,
    details      TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_timeline_user_job
    ON application_timeline(user_id, job_id, occurred_at DESC);
