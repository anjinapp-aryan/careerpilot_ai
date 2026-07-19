-- CareerPilot AI — Gap D: Real Browser Automation (guest/no-login apply flows only)
-- HIGH RISK. Additive only, ships DARK (browser.automation.enabled defaults false). No code path
-- in this build submits a login-required application; guest-apply automation (Greenhouse/Lever
-- only, see ai.careerpilot.execution.ats.GuestApplyEligibility) still requires a NEW mandatory
-- human "approve this specific filled form" decision (screenshot-evidenced) before any submit click.
--
-- execution_screenshot: one row per captured screenshot of a filled (not-yet-submitted) guest-apply
-- form — the evidence attached to the new approval_queue.approval_type='FORM_SCREENSHOT' subtype.
--
-- approval_queue gains three additive, nullable columns so the EXISTING package-level approval flow
-- (Phase 2E.4) is completely unaffected: approval_type defaults to 'APPLICATION_PACKAGE' for every
-- pre-existing and future package approval row; execution_id/screenshot_id are populated only for
-- the new 'FORM_SCREENSHOT' subtype.
--
-- NOTE: same Neon hand-apply convention as V4-V61 (idempotent DDL).

CREATE TABLE IF NOT EXISTS execution_screenshot (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id           UUID NOT NULL REFERENCES application_execution(id) ON DELETE CASCADE,
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    storage_key            VARCHAR(500) NOT NULL,
    approval_queue_entry_id UUID,
    captured_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_execution_screenshot_execution
    ON execution_screenshot(execution_id, captured_at DESC);
CREATE INDEX IF NOT EXISTS idx_execution_screenshot_approval
    ON execution_screenshot(approval_queue_entry_id);

ALTER TABLE approval_queue ADD COLUMN IF NOT EXISTS approval_type VARCHAR(24) NOT NULL DEFAULT 'APPLICATION_PACKAGE';
ALTER TABLE approval_queue ADD COLUMN IF NOT EXISTS execution_id UUID;
ALTER TABLE approval_queue ADD COLUMN IF NOT EXISTS screenshot_id UUID;
CREATE INDEX IF NOT EXISTS idx_approval_queue_type
    ON approval_queue(approval_type, status, requested_at DESC);
