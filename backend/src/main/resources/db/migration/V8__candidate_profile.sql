-- CareerPilot AI — Candidate Intelligence Profile (Phase 1).
-- Additive only: two brand-new tables, no change to any existing table, so Hibernate's
-- `validate` stays green and Resume Upload / AI Workflow / Optimizer / Chat / Dashboard /
-- Job discovery + recommendation flows do not regress. Idempotent so it can be hand-applied
-- against the managed Neon instance (which baselines rather than auto-migrating) — same
-- convention as V4–V7.
--
-- The profile is the canonical, AI-analyzed candidate record: resume-derived intelligence
-- MERGED with a snapshot of candidate_preferences. It does NOT duplicate resume text — only
-- structured intelligence is stored. Behavior is gated by CANDIDATE_PROFILE_ENABLED (default
-- false); these tables are inert until the flag is flipped, and dropping them has zero impact
-- on existing flows (nothing references them yet).

-- ── Canonical candidate intelligence profile (one row per user) ──────────────────────
CREATE TABLE IF NOT EXISTS candidate_profiles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    resume_id           UUID REFERENCES resumes(id) ON DELETE SET NULL,

    -- AI-derived from the resume (cached; re-extracted only when the resume changes)
    years_experience    INT,
    current_role_title  VARCHAR(160),              -- "current_role" is a reserved Postgres keyword
    seniority_level     VARCHAR(40),               -- Junior|Mid|Senior|Lead|Architect|Principal|…
    target_roles        JSONB,                     -- ["Solution Architect", …]
    skills              JSONB,                     -- ["Java","Spring Boot", …]
    domains             JSONB,                     -- ["Finance","Retail"]
    languages           JSONB,                     -- ["English","German"]
    profile_summary     TEXT,
    confidence_score    NUMERIC,                   -- 0.0–1.0 extraction confidence

    -- Snapshot of candidate_preferences (editable source of truth lives in that table)
    preferred_countries JSONB,
    preferred_cities    JSONB,
    work_modes          JSONB,                     -- ["Remote","Hybrid","Onsite"]
    visa_required       BOOLEAN,
    salary_currency     VARCHAR(10),
    salary_min          NUMERIC,
    salary_target       NUMERIC,
    excluded_roles      JSONB,

    -- Hash of the resume parsed_text the AI extraction was derived from. Lets a
    -- preferences-only update re-merge without paying for a fresh LLM extraction.
    resume_fingerprint  VARCHAR(64),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_candidate_profile_user UNIQUE (user_id)
);

CREATE INDEX IF NOT EXISTS idx_candidate_profiles_user       ON candidate_profiles(user_id);
CREATE INDEX IF NOT EXISTS idx_candidate_profiles_resume     ON candidate_profiles(resume_id);
CREATE INDEX IF NOT EXISTS idx_candidate_profiles_updated_at ON candidate_profiles(updated_at DESC);

-- ── Profile version history (audit / rollback / explainability) ──────────────────────
-- Every regeneration writes a before/after snapshot. Supports audit, rollback, and the
-- /history endpoint; future analytics can read it without touching the live profile.
CREATE TABLE IF NOT EXISTS candidate_profile_versions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    profile_id  UUID REFERENCES candidate_profiles(id) ON DELETE CASCADE,
    before      JSONB,                             -- null on first creation
    after       JSONB,
    reason      VARCHAR(60) NOT NULL,              -- RESUME_UPLOADED|RESUME_OPTIMIZED|PREFERENCES_UPDATED|MANUAL_REBUILD
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_candidate_profile_versions_user
    ON candidate_profile_versions(user_id, created_at DESC);
