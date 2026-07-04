-- CareerPilot AI — Phase 3A.3: Email Intelligence Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- INERT: there is no mailbox integration and nothing fetches email. Classification/extraction is
-- deterministic (EmailClassifier); rows are only ever created through the (dark) manual ingest path.

CREATE TABLE IF NOT EXISTS application_email (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID NOT NULL,
    job_id     UUID,
    subject    VARCHAR(512),
    body       TEXT,
    category   VARCHAR(32) NOT NULL,   -- ACKNOWLEDGEMENT|ASSESSMENT|INTERVIEW|OFFER|REJECTION|WITHDRAWAL|UNKNOWN
    confidence DOUBLE PRECISION,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_email_user_job
    ON application_email(user_id, job_id, created_at DESC);

CREATE TABLE IF NOT EXISTS email_extraction (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_id       UUID NOT NULL REFERENCES application_email(id) ON DELETE CASCADE,
    company        VARCHAR(255),
    job_title      VARCHAR(255),
    interview_type VARCHAR(32),
    extracted_date VARCHAR(64),
    salary         VARCHAR(64),
    confidence     DOUBLE PRECISION,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_email_extraction_email
    ON email_extraction(email_id);

CREATE TABLE IF NOT EXISTS email_audit (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email_id   UUID,
    action     VARCHAR(32) NOT NULL,
    detail     TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
