-- Phase 13A — ATS Validation Campaign: durable, append-only history of every validation run.
--
-- Phase 12C.5 built the harness and kept its results in a bounded in-memory map (one entry per ATS,
-- lost on restart). That is enough to answer "is this ATS automatable right now" and cannot answer
-- the questions 13A actually needs:
--   * how many pages of this ATS have we tested?
--   * has confidence moved since last week?
--   * did a selector change silently degrade us?
-- None of those are answerable without history, so this table is the genuine gap.
--
-- APPEND-ONLY BY CONTRACT. There is no UPDATE path anywhere in the code that writes here. A
-- validation run is an observation of how an employer's page looked at a moment in time; rewriting
-- one would destroy the very baseline drift detection compares against.
CREATE TABLE IF NOT EXISTS ats_validation_run (
    id                       UUID PRIMARY KEY,

    -- Who triggered it. Retained for auditing; never exposed on the unauthenticated diagnostics
    -- endpoint, which serves aggregates only.
    user_id                  UUID,
    ats_platform             VARCHAR(40)  NOT NULL,

    -- The exact posting validated. Needed to reproduce a run and to tell "this ATS regressed" apart
    -- from "this particular posting is unusual". Same visibility rule as user_id.
    url                      TEXT         NOT NULL,
    url_hash                 VARCHAR(64)  NOT NULL,

    status                   VARCHAR(20)  NOT NULL,   -- COMPLETED | FAILED | REFUSED
    message                  TEXT,

    -- Deterministic score from AutomationConfidence. Never an estimate.
    confidence_score         INT          NOT NULL,
    confidence_band          VARCHAR(10)  NOT NULL,   -- HIGH | MEDIUM | LOW
    ready                    BOOLEAN      NOT NULL,

    -- SelectorCoverage, stored as discrete columns rather than a JSON blob so trend queries are
    -- plain SQL aggregates instead of per-row JSON parsing on a 1-vCPU box.
    total_controls           INT          NOT NULL DEFAULT 0,
    fillable_controls        INT          NOT NULL DEFAULT 0,
    supported_controls       INT          NOT NULL DEFAULT 0,
    unsupported_controls     INT          NOT NULL DEFAULT 0,
    unknown_controls         INT          NOT NULL DEFAULT 0,
    required_controls        INT          NOT NULL DEFAULT 0,
    mapped_controls          INT          NOT NULL DEFAULT 0,
    missing_required_values  INT          NOT NULL DEFAULT 0,

    -- Timings, for the performance report and for spotting a page that got slower.
    navigation_ms            BIGINT       NOT NULL DEFAULT 0,
    discovery_ms             BIGINT       NOT NULL DEFAULT 0,
    planning_ms              BIGINT       NOT NULL DEFAULT 0,
    total_ms                 BIGINT       NOT NULL DEFAULT 0,

    -- Page environment: what made this page hard, recorded alongside the score it produced.
    spa_framework            VARCHAR(40),
    iframe_count             INT          NOT NULL DEFAULT 0,
    shadow_root_count        INT          NOT NULL DEFAULT 0,
    captcha_detected         BOOLEAN      NOT NULL DEFAULT FALSE,
    console_error_count      INT          NOT NULL DEFAULT 0,

    screenshot_key           TEXT,

    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- The trend query: latest N runs for one ATS, newest first.
CREATE INDEX IF NOT EXISTS idx_ats_validation_run_platform_time
    ON ats_validation_run (ats_platform, created_at DESC);

-- Drift detection compares the same posting over time, which needs the per-URL series.
CREATE INDEX IF NOT EXISTS idx_ats_validation_run_url_time
    ON ats_validation_run (url_hash, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ats_validation_run_user_time
    ON ats_validation_run (user_id, created_at DESC);
