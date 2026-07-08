-- CareerPilot AI — Phase 7.11: Application Package Intelligence (validation history)
-- ADDITIVE ONLY. Immutable, append-only history of every validation run over an application package
-- version (Part 4 + Part 5). One row per validation attempt so the full lineage of READY/HUMAN_REVIEW/
-- BLOCKED verdicts is auditable and comparable. `checks` is a compact JSON array of the individual
-- gate results (resume selected, tailored, ATS available, recommendation available, company research,
-- learning snapshot, required skills, mandatory fields). Never updated in place — a re-validation
-- inserts a new row.
-- Same Neon hand-apply / idempotent-DDL convention as V4-V52. NOT applied here.

CREATE TABLE IF NOT EXISTS application_package_validation (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_package_id UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    user_id                UUID NOT NULL,
    job_id                 UUID NOT NULL,
    package_version        INTEGER NOT NULL,
    status                 VARCHAR(16) NOT NULL,   -- READY | HUMAN_REVIEW | BLOCKED
    blocking_reason        TEXT,
    checks                 TEXT,                   -- compact JSON array of individual gate results
    correlation_id         UUID,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_app_package_validation_package
    ON application_package_validation(application_package_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_package_validation_user
    ON application_package_validation(user_id, created_at DESC);
