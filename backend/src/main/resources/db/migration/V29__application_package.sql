-- CareerPilot AI — Phase 2D.6: Application Package Builder
-- Additive only. Assembles the complete application package for a (user, job): tailored resume +
-- cover letter + ATS analysis + gap analysis + explainability, with full lineage. Reference-based
-- (stores row ids, not copies) so the package always reflects exactly which artifact versions it
-- was assembled from:
--   application_package          = head row per (user_id, job_id), current package_version
--   application_package_versions = immutable append-only assembly history
--   application_package_audit    = append-only outcomes (ASSEMBLED | INCOMPLETE | ERROR)
--
-- NOTE: same Neon hand-apply convention as V4-V28 (idempotent DDL).

CREATE TABLE IF NOT EXISTS application_package (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    application_id            UUID,
    resume_id                 UUID,
    resume_tailoring_id       UUID REFERENCES resume_tailoring(id),
    cover_letter_id           UUID REFERENCES cover_letter(id),
    ats_analysis_id           UUID REFERENCES resume_ats_analysis(id),
    gap_analysis_id           UUID REFERENCES resume_gap_analysis(id),
    ats_explanation_id        UUID REFERENCES resume_ats_explanation(id),
    candidate_profile_version UUID,
    behavior_profile_version  TIMESTAMPTZ,
    recommendation_audit_id   UUID,
    package_version           INTEGER NOT NULL,
    status                    VARCHAR(20) NOT NULL DEFAULT 'ASSEMBLED', -- ASSEMBLED | INCOMPLETE
    metadata                  TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_application_package_user_job UNIQUE (user_id, job_id)
);

CREATE TABLE IF NOT EXISTS application_package_versions (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_package_id    UUID NOT NULL REFERENCES application_package(id) ON DELETE CASCADE,
    user_id                   UUID NOT NULL,
    job_id                    UUID NOT NULL,
    resume_id                 UUID,
    resume_tailoring_id       UUID,
    cover_letter_id           UUID,
    ats_analysis_id           UUID,
    gap_analysis_id           UUID,
    ats_explanation_id        UUID,
    candidate_profile_version UUID,
    behavior_profile_version  TIMESTAMPTZ,
    recommendation_audit_id   UUID,
    package_version           INTEGER NOT NULL,
    status                    VARCHAR(20) NOT NULL,
    metadata                  TEXT,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_package_versions_head
    ON application_package_versions(application_package_id, package_version DESC);

CREATE TABLE IF NOT EXISTS application_package_audit (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID NOT NULL,
    job_id                 UUID NOT NULL,
    application_package_id UUID,
    package_version        INTEGER,
    outcome                VARCHAR(24) NOT NULL, -- ASSEMBLED | INCOMPLETE | ERROR
    reason                 TEXT,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_app_package_audit_user
    ON application_package_audit(user_id, created_at DESC);
