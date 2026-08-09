-- Guided Apply — closes the previously-documented WAITING_MANUAL_SUBMISSION dead end
-- (application_submission_session.status had zero legal outgoing transitions from that state).
--
-- Two nullable columns only. No new table: blocker reason is deliberately NOT persisted here (or
-- anywhere) — it is derived read-time from the existing application_execution.failure_reason text
-- via ai.careerpilot.domain.GuidedApplyReason#fromFailureReason, matching this codebase's existing
-- "derive, don't trust a lagging column" convention (WorkflowService#deriveDisplayStatus,
-- ApplicationCardService#deriveAutomationHealth). Only the candidate's own explicit confirmation —
-- a fact CareerPilot cannot derive from anything else — needs a place to live.
ALTER TABLE application_submission_session
    ADD COLUMN IF NOT EXISTS user_reported_submitted_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS user_submission_note        TEXT;
