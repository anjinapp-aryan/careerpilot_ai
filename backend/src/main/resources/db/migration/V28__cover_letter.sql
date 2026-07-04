-- CareerPilot AI — Phase 2D.5: Cover Letter Engine
-- Additive only. LLM-generated, validator-guarded (no fabricated skills/experience/certs/
-- companies/titles) personalized cover letters, versioned v1.N per (user, job):
--   cover_letter          = head row per (user_id, job_id) pointing at the current version
--   cover_letter_versions = immutable append-only history (one row per generation)
--   cover_letter_audit    = append-only outcomes (GENERATED | VALIDATION_REJECTED | CACHE_HIT | ERROR)
--
-- NOTE: same Neon hand-apply convention as V4-V27 (idempotent DDL).

CREATE TABLE IF NOT EXISTS cover_letter (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    job_id                    UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    application_id            UUID,
    resume_tailoring_id       UUID REFERENCES resume_tailoring(id),
    candidate_profile_version UUID,
    behavior_profile_version  TIMESTAMPTZ,
    version                   INTEGER NOT NULL,
    provider                  VARCHAR(60),
    status                    VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    content                   TEXT NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_cover_letter_user_job UNIQUE (user_id, job_id)
);

CREATE TABLE IF NOT EXISTS cover_letter_versions (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    cover_letter_id           UUID NOT NULL REFERENCES cover_letter(id) ON DELETE CASCADE,
    user_id                   UUID NOT NULL,
    job_id                    UUID NOT NULL,
    resume_tailoring_id       UUID,
    candidate_profile_version UUID,
    behavior_profile_version  TIMESTAMPTZ,
    version                   INTEGER NOT NULL,
    provider                  VARCHAR(60),
    status                    VARCHAR(20) NOT NULL DEFAULT 'GENERATED',
    content                   TEXT NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cover_letter_versions_head
    ON cover_letter_versions(cover_letter_id, version DESC);

CREATE TABLE IF NOT EXISTS cover_letter_audit (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    job_id          UUID NOT NULL,
    cover_letter_id UUID,
    version         INTEGER,
    outcome         VARCHAR(24) NOT NULL, -- GENERATED | VALIDATION_REJECTED | CACHE_HIT | ERROR
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_cover_letter_audit_user
    ON cover_letter_audit(user_id, created_at DESC);
