-- CareerPilot AI — Phase 6.1: Learning Event Pipeline
-- Additive only, ships DARK. NOT applied by this work.
--
-- learning_event is the append-only capture of every outcome-bearing signal the learning engine
-- observes (applications, interviews, offers, resume selections, recommendation decisions,
-- workflow completions), captured as a SECOND listener on existing Phase 2D/2E/3A events — no
-- existing publisher is modified. learning_metrics is a per-stage-execution audit log (one row
-- per pipeline stage run), backing the diagnostics endpoints' event/latency/health figures.

CREATE TABLE IF NOT EXISTS learning_event (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    correlation_id UUID,
    user_id        UUID NOT NULL,
    job_id         UUID,
    event_type     VARCHAR(48) NOT NULL,
    resume_version VARCHAR(128),
    workflow_id    VARCHAR(128),
    company        VARCHAR(255),
    country        VARCHAR(128),
    role_family    VARCHAR(64),
    skills         TEXT,
    industry       VARCHAR(64),
    salary_band    VARCHAR(64),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_learning_event_user ON learning_event(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_learning_event_type ON learning_event(event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_learning_event_correlation ON learning_event(correlation_id);

CREATE TABLE IF NOT EXISTS learning_metrics (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    learning_event_id UUID,
    stage           VARCHAR(48) NOT NULL, -- EVENT_CAPTURE|SUCCESS_PATTERN|FAILURE_PATTERN|RECOMMENDATION_LEARNING|RESUME_LEARNING|CAREER_LEARNING
    status          VARCHAR(24) NOT NULL, -- SUCCESS|FAILED
    latency_ms      BIGINT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_learning_metrics_stage ON learning_metrics(stage, created_at DESC);
