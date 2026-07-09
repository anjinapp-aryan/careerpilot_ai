-- CareerPilot AI — Phase 7.12: AI Review Pipeline
-- ADDITIVE ONLY. The final quality gate that REVIEWS (never mutates) the Phase 7.11 Application
-- Package before Human Review / Auto Apply. One head row per package (current review) plus an
-- immutable append-only history of every review run. Stores each reviewer's score, the consistency
-- verdict, the aggregate quality, and the final verdict (READY | HUMAN_REVIEW | BLOCKED). Reasons is a
-- compact JSON array. Reviewers read already-computed artifacts (resume/ATS/recommendation/company/
-- learning) — this table holds only their assessments, never copies of the artifacts.
-- Same Neon hand-apply / idempotent-DDL convention as V4-V53. NOT applied here.

CREATE TABLE IF NOT EXISTS application_review (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_package_id UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    user_id                UUID NOT NULL,
    job_id                 UUID NOT NULL,
    package_version        INTEGER NOT NULL,
    resume_score           INTEGER,
    ats_score              INTEGER,
    company_fit_score      INTEGER,
    learning_confidence    INTEGER,
    consistency_status     VARCHAR(16),   -- PASS | WARNING | FAIL
    quality_score          INTEGER,       -- 0-100
    quality_category       VARCHAR(16),   -- EXCELLENT | STRONG | GOOD | WEAK | BLOCKED
    verdict                VARCHAR(16) NOT NULL, -- READY | HUMAN_REVIEW | BLOCKED
    confidence             INTEGER,       -- 0-100
    reasons                TEXT,          -- compact JSON array of {reviewer, score, reasons[]}
    correlation_id         UUID,
    review_version         INTEGER NOT NULL DEFAULT 1,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_review_package UNIQUE (application_package_id)
);
CREATE INDEX IF NOT EXISTS idx_application_review_user ON application_review(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS application_review_history (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_review_id  UUID NOT NULL REFERENCES application_review(id) ON DELETE CASCADE,
    application_package_id UUID NOT NULL,
    user_id                UUID NOT NULL,
    job_id                 UUID NOT NULL,
    package_version        INTEGER NOT NULL,
    review_version         INTEGER NOT NULL,
    resume_score           INTEGER,
    ats_score              INTEGER,
    company_fit_score      INTEGER,
    learning_confidence    INTEGER,
    consistency_status     VARCHAR(16),
    quality_score          INTEGER,
    quality_category       VARCHAR(16),
    verdict                VARCHAR(16) NOT NULL,
    confidence             INTEGER,
    reasons                TEXT,
    correlation_id         UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_application_review_history_review
    ON application_review_history(application_review_id, created_at DESC);
