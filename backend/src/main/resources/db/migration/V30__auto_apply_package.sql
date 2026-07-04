-- CareerPilot AI — Phase 2D.7: Auto Apply Package Preparation
-- Additive only. PREPARATION ONLY — no browser automation exists or is triggered anywhere.
-- Deterministic readiness assessment of an assembled application package: how would this job be
-- applied to (method, login/questionnaire/upload requirements) and is it SAFE_TO_APPLY /
-- REQUIRES_REVIEW / MANUAL_ONLY.
--
-- NOTE: same Neon hand-apply convention as V4-V29 (idempotent DDL).

CREATE TABLE IF NOT EXISTS auto_apply_package (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                 UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    application_package_id UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    application_id         UUID,
    provider               VARCHAR(60),          -- job source (remoteok | arbeitnow | adzuna | jooble | manual)
    application_method     VARCHAR(30) NOT NULL, -- EXTERNAL_URL | EMAIL | UNKNOWN
    requires_login         BOOLEAN NOT NULL DEFAULT true,
    requires_questionnaire BOOLEAN NOT NULL DEFAULT false,
    requires_upload        BOOLEAN NOT NULL DEFAULT true,
    readiness_score        INTEGER NOT NULL,
    status                 VARCHAR(20) NOT NULL, -- SAFE_TO_APPLY | REQUIRES_REVIEW | MANUAL_ONLY
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auto_apply_user_job
    ON auto_apply_package(user_id, job_id, created_at DESC);

CREATE TABLE IF NOT EXISTS auto_apply_package_audit (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL,
    job_id                UUID NOT NULL,
    auto_apply_package_id UUID,
    outcome               VARCHAR(24) NOT NULL, -- PREPARED | ERROR
    reason                TEXT,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_auto_apply_audit_user
    ON auto_apply_package_audit(user_id, created_at DESC);
