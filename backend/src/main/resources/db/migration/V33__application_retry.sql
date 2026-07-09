-- CareerPilot AI — Phase 2E.6: Retry & Recovery Engine
-- HIGH RISK PHASE. Additive only, ships DARK. NOT applied by this work.
--
-- application_retry is the append-only decision log for how a failed execution attempt was handled.
-- The failure_class -> action policy (see RetryPolicyService) is:
--   network=RETRY, captcha=PAUSE, login_failed=PAUSE, validation_failed=STOP, duplicate=STOP,
--   rate_limited=RETRY_BACKOFF. Maximum 3 retries; beyond that -> STOP.
--
-- NOTE: same Neon hand-apply convention as V4-V32 (idempotent DDL).

CREATE TABLE IF NOT EXISTS application_retry (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_execution_id UUID NOT NULL REFERENCES application_execution(id) ON DELETE CASCADE,
    user_id                  UUID NOT NULL,
    job_id                   UUID NOT NULL,
    attempt                  INTEGER NOT NULL,
    failure_class            VARCHAR(24) NOT NULL, -- NETWORK|CAPTCHA|LOGIN_FAILED|VALIDATION_FAILED|DUPLICATE|RATE_LIMITED|UNKNOWN
    action                   VARCHAR(16) NOT NULL, -- RETRY|RETRY_BACKOFF|PAUSE|STOP
    backoff_ms               BIGINT NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_retry_execution
    ON application_retry(application_execution_id, attempt);
