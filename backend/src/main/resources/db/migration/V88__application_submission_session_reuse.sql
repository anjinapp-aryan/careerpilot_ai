-- Artifact reuse layer: Step 6 (field mapping + question/answer generation) of
-- ApplicationSubmissionSessionService currently re-runs the AI Gateway 11 times on EVERY Apply
-- click for the same job, even when nothing that could change the answers has changed. These
-- columns let a session record what it reused and from where — additive, nullable, no backfill.
-- Existing sessions simply have NULL here and behave exactly as FULL_BUILD on their next read.
ALTER TABLE application_submission_session
    ADD COLUMN job_fingerprint VARCHAR(500),
    ADD COLUMN star_story_id UUID,
    ADD COLUMN answers_reuse_decision VARCHAR(30),
    ADD COLUMN answers_reused_from_session_id UUID;
