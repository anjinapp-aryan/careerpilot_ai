-- Phase F1 — per-step state for multi-page employer forms.
--
-- WHY THIS TABLE EXISTS AT ALL
--
-- A human approval is asynchronous and can take hours. The browser pool runs with
-- browser.automation.pool.max-leases = 1 and a 180-second lease TTL, so holding a BrowserContext
-- open across an approval would both deadlock the single production lease and be reclaimed out from
-- under us by the TTL sweep anyway.
--
-- Therefore the session CANNOT span the approval, and step progress has to live somewhere durable
-- instead of in a live browser. Each page is its own lease cycle:
--
--   fill page N -> screenshot -> enqueue approval -> RELEASE LEASE -> (human) -> new lease ->
--   re-navigate -> replay pages 1..N -> advance -> fill page N+1 -> ...
--
-- Replay is safe precisely because fills are deterministic: every value comes from the same
-- verified profile fields and human-approved answers, so replaying page 1 types exactly what the
-- reviewer already approved. No new data is ever introduced on a replay, and SUBMIT is never
-- clicked during one.
--
-- APPEND-ONLY PER STEP. A step row is inserted when the step is first filled and updated only to
-- record its approval outcome. There is no path that rewrites a step's captured evidence — the
-- screenshot a human approved must remain exactly what they saw.
CREATE TABLE IF NOT EXISTS execution_step (
    id                      UUID PRIMARY KEY,
    execution_id            UUID         NOT NULL,
    user_id                 UUID         NOT NULL,

    -- 1-based page number within the wizard.
    step_number             INT          NOT NULL,

    -- PENDING_APPROVAL | APPROVED | REJECTED | BLOCKED | FAILED
    status                  VARCHAR(24)  NOT NULL,

    -- The page this step was captured on. Stored so a post-approval replay can detect that the
    -- employer moved the form under us rather than silently filling a different page.
    page_url                TEXT,

    -- Evidence linkage. Reuses the existing execution_screenshot / approval_queue rows rather than
    -- duplicating either.
    screenshot_id           UUID,
    approval_queue_entry_id UUID,

    -- The full reviewer bundle (controls, filled values, gaps, warnings, confidence) as rendered
    -- at capture time. Persisted rather than recomputed so what the reviewer saw is auditable
    -- forever, even after the employer changes the page.
    bundle_json             JSONB,

    -- Bounded replay/advance attempts, so a wizard that never settles cannot loop.
    attempt_count           INT          NOT NULL DEFAULT 1,

    -- True when the navigator reported this page as the terminal step.
    final_step              BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- One row per (execution, step). The unique constraint is what makes "no page may be filled
    -- twice without a fresh approval" enforceable in the database and not only in code.
    CONSTRAINT uq_execution_step UNIQUE (execution_id, step_number)
);

CREATE INDEX IF NOT EXISTS idx_execution_step_execution ON execution_step (execution_id, step_number);
CREATE INDEX IF NOT EXISTS idx_execution_step_status ON execution_step (user_id, status);
