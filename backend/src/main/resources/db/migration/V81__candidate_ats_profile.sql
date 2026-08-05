-- Phase C — Candidate ATS Profile: the user-owned, verified source of truth for the identity,
-- contact, employment, education and work-authorisation facts real employer forms require.
--
-- WHY A NEW TABLE RATHER THAN COLUMNS ON candidate_profiles
--
-- candidate_profiles is a DERIVED snapshot, not a user-owned record. CandidateProfileService.upsert
-- takes the existing row and overwrites every field it knows about from resume-extracted AI output
-- plus a candidate_preferences snapshot, on every rebuild — MANUAL_REBUILD, SCHEDULED_REBUILD, and
-- PREFERENCES_UPDATED. Placing a hand-entered phone number in that row would make it survive only
-- for as long as nobody adds a line to upsert(). That is a data-loss defect waiting to be
-- introduced by an unrelated change, and this phase's own requirement is zero data loss.
--
-- This table is therefore user-owned and immune to profile rebuilds by construction: nothing in
-- CandidateProfileService references it. It mirrors the role candidate_preferences already plays —
-- the editable source of truth — rather than inventing a new pattern.
--
-- NO DUPLICATION. Facts that already have a real home are NOT repeated here and are resolved from
-- their existing owner by FieldMappingService:
--   * years of experience, skills, seniority  -> candidate_profiles (resume-derived)
--   * expected salary, visa-sponsorship-required, home country -> candidate_preferences snapshot
--   * full name, email                        -> users
-- Two writable copies of one fact is how a "single source of truth" stops being one.
--
-- ADDITIVE ONLY: new table, no existing column altered, renamed or dropped. Every column is
-- nullable — a profile is filled in progressively, and a partially-complete row is the normal
-- state, not an error.
CREATE TABLE IF NOT EXISTS candidate_ats_profile (
    id                        UUID PRIMARY KEY,
    user_id                   UUID         NOT NULL UNIQUE,

    -- ── Contact ──
    phone                     VARCHAR(40),
    address_line1             VARCHAR(200),
    address_line2             VARCHAR(200),
    city                      VARCHAR(120),
    state_province            VARCHAR(120),
    postal_code               VARCHAR(20),
    country                   VARCHAR(80),

    -- ── Professional links ──
    linkedin_url              VARCHAR(300),
    github_url                VARCHAR(300),
    portfolio_url             VARCHAR(300),
    personal_website_url      VARCHAR(300),

    -- ── Current employment ──
    current_company           VARCHAR(200),
    current_title             VARCHAR(200),
    notice_period             VARCHAR(80),
    -- Current salary has no home anywhere in the schema. Expected salary deliberately does NOT
    -- appear here: candidate_preferences.salary_expectation_max already owns it.
    current_salary            NUMERIC(14,2),
    current_salary_currency   VARCHAR(8),

    -- ── Education ──
    highest_education         VARCHAR(80),
    degree                    VARCHAR(160),
    field_of_study            VARCHAR(160),
    university                VARCHAR(200),
    graduation_year           INT,

    -- ── Work authorisation ──
    -- Separate from candidate_preferences.visa_sponsorship_required, which is a job-search
    -- preference ("only show me jobs that sponsor"). These are statements of present legal fact
    -- that an employer form asks directly, and conflating the two would answer a legal question
    -- with a search filter.
    work_authorization        VARCHAR(120),
    visa_status               VARCHAR(120),
    citizenship               VARCHAR(80),
    security_clearance        VARCHAR(120),

    -- ── Lists (JSONB, same convention as candidate_profiles' *_json columns) ──
    languages_json            JSONB,
    certifications_json       JSONB,

    -- ── Provenance ──
    -- fieldName -> FieldVerificationSource name. One JSONB column rather than 25 paired *_source
    -- columns: the set of tracked fields will grow, and a schema change per new field is exactly
    -- the friction that causes provenance to be skipped. Automation reads this before every value
    -- it uses; a field absent from this map is treated as unverified, never as trusted.
    field_sources             JSONB,

    created_at                TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- One row per user; the unique constraint above is the real guard. This index serves the lookup.
CREATE INDEX IF NOT EXISTS idx_candidate_ats_profile_user ON candidate_ats_profile (user_id);
