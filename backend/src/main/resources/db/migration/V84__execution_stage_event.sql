-- P5 — per-stage execution timeline.
--
-- WHY THIS TABLE EXISTS AT ALL
--
-- The platform already records four different things about an execution, and none of them answers
-- "where exactly did this application stop, and how long did each part take":
--
--   application_execution.checkpoint      a SINGLE furthest-phase string, overwritten each time.
--                                         Tells you the last phase reached, never the sequence,
--                                         never a duration, never why that phase was the last one.
--   application_execution_audit           append-only, but keyed on OUTCOME (RECOVERY_RETRY,
--                                         VERIFICATION_FAILED, ...), not on pipeline stage, and
--                                         carries no timing at all.
--   execution_screenshot                  three fixed phases; evidence, not a timeline.
--   application_timeline (Phase 3A)       per (user, JOB) human-readable lifecycle events —
--                                         a different grain entirely. One job has many executions.
--
-- This table is the missing grain: one row per stage transition of one execution run, with its own
-- start, end and duration. It is what makes the Operations Center able to answer "stopped at
-- FIELD_FILL, 2418ms in, because a required salary answer had no verified value" without anyone
-- opening a log file.
--
-- APPEND-ONLY. A row is inserted when a stage starts and updated exactly once to close it out
-- (ended_at / duration_ms / status / reason). Nothing rewrites a closed row, and there is no delete
-- path — the same contract ats_validation_run uses, enforced by the absence of a way to violate it.
--
-- BOUNDED BY CONSTRUCTION. Stages are a fixed enum and an execution passes each at most a handful
-- of times, so this grows with executions, not unboundedly per execution. The existing
-- retention.* machinery can age it out alongside the other append-only ledgers.
CREATE TABLE IF NOT EXISTS execution_stage_event (
    id                 UUID PRIMARY KEY,
    execution_id       UUID        NOT NULL,
    user_id            UUID        NOT NULL,
    job_id             UUID,
    -- Monotonic per execution. Two stages can share a millisecond on a fast path, so ordering is
    -- explicit rather than inferred from started_at — a timeline that reorders itself is worse
    -- than no timeline.
    sequence_no        INTEGER     NOT NULL,
    stage              VARCHAR(48) NOT NULL,
    -- STARTED while in flight; COMPLETED / FAILED / SKIPPED once closed.
    status             VARCHAR(16) NOT NULL,
    started_at         TIMESTAMPTZ NOT NULL,
    ended_at           TIMESTAMPTZ,
    duration_ms        BIGINT,
    -- Only set on FAILED. One of the fixed FailureCategory values, so failures aggregate.
    failure_category   VARCHAR(32),
    reason             TEXT,
    -- Small, stage-specific metadata (question counts, confidence, lease id, page title...).
    -- Deliberately JSONB and deliberately small: this is a diagnostic breadcrumb, not a payload
    -- store, and it is served to an authenticated owner only.
    detail             JSONB,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The only read pattern that matters: one execution's timeline, in order.
CREATE INDEX IF NOT EXISTS idx_execution_stage_event_execution
    ON execution_stage_event(execution_id, sequence_no);

-- Aggregate reads for the metrics/dashboard side: "which stage fails most", "average duration per
-- stage", both scoped by time.
CREATE INDEX IF NOT EXISTS idx_execution_stage_event_stage_status
    ON execution_stage_event(stage, status, created_at);
