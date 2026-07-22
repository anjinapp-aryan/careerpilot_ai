-- Phase 7.16.3 — Automation Recovery & Retry Center. Additive only.
-- Not applied by this work — hand-apply to Neon, same convention as V33/V62/V64.

ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS checkpoint VARCHAR(64);
ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS retry_of_execution_id UUID;
ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS next_retry_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_app_execution_retry_due
    ON application_execution (execution_status, next_retry_at);

-- Phase 7.16.3 — screenshot timeline: which moment this screenshot captures.
-- Existing rows (pre-submit approval screenshots) are implicitly BEFORE_SUBMIT; backfilled below.
ALTER TABLE execution_screenshot ADD COLUMN IF NOT EXISTS phase VARCHAR(32);
UPDATE execution_screenshot SET phase = 'BEFORE_SUBMIT' WHERE phase IS NULL;
