-- CareerPilot AI — Phase 7.16: Real Application Submission Pipeline
-- Additive only, ships DARK (application.submission.enabled=false). NOT applied by this work —
-- hand-applied to Neon later, same convention as V50-V56 (see CLAUDE.md's baseline-on-migrate note).
--
-- application_submission_session groups one user-triggered Apply click into a single trackable,
-- user-facing unit spanning validation -> package/review/company/STAR readiness -> field mapping ->
-- question detection/answering -> submission-strategy resolution -> optional human approval ->
-- execution -> verification -> tracking -> learning. It links (by id, never by copy) into the
-- existing dormant execution/packageintel/review/companyintel/story rows it orchestrates.
--
-- application_submission_answer stores the generated Q&A for traceability/approval display.

CREATE TABLE IF NOT EXISTS application_submission_session (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL,
    job_id                    UUID NOT NULL,
    application_id            UUID,
    resume_id                 UUID,
    resume_tailoring_id       UUID,
    application_package_id    UUID,
    application_review_id     UUID,
    company_knowledge_id      UUID,
    approval_queue_entry_id   UUID,
    application_execution_id  UUID,
    status                    VARCHAR(32) NOT NULL,
    submission_method         VARCHAR(16) NOT NULL DEFAULT 'MANUAL',   -- AUTO | MANUAL
    provider                  VARCHAR(48),
    correlation_id            UUID,
    failure_reason            TEXT,
    started_at                TIMESTAMPTZ,
    completed_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_submission_session_user_job
    ON application_submission_session(user_id, job_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_submission_session_status
    ON application_submission_session(status);
CREATE INDEX IF NOT EXISTS idx_app_submission_session_approval_queue
    ON application_submission_session(approval_queue_entry_id);

CREATE TABLE IF NOT EXISTS application_submission_answer (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        UUID NOT NULL,
    question_text     TEXT NOT NULL,
    question_category VARCHAR(32) NOT NULL,
    answer_text       TEXT,
    source_refs       JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_submission_answer_session
    ON application_submission_answer(session_id);
