-- CareerPilot AI — Phase 7.16.1: Submission Verification & Evidence. Additive only, ships DARK
-- (application.execution.enabled=false, browser.automation.enabled=false — unchanged from before
-- this phase). Not applied by this work — hand-applied to Neon later, same convention as
-- V50-V63 (see CLAUDE.md's baseline-on-migrate note).
--
-- Closes the trust gap identified in the Phase 7.16 architecture review: ApplicationExecution
-- previously had no way to record WHY it reached SUBMITTED — "VERIFIED" (on the separate
-- application_submission_session table) was a bare state transition with no evidence behind it.
-- These columns are populated only by SubmissionVerificationService, never fabricated: NULL means
-- "we genuinely don't have this," not "not applicable."

ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS confirmation_number   TEXT;
ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS verification_status   VARCHAR(32);
ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS verification_method   VARCHAR(64);
ALTER TABLE application_execution ADD COLUMN IF NOT EXISTS verified_at           TIMESTAMPTZ;

-- Phase 7.16.1 — new SubmissionStateMachine states (submission/SubmissionStateMachine.java),
-- inserted between the existing SUBMITTED and VERIFIED/TRACKING states. No column change needed:
-- application_submission_session.status is already a plain VARCHAR, not a DB-level enum/check
-- constraint — these are additive application-level state names only.
-- New states: VERIFYING, VERIFICATION_FAILED.
