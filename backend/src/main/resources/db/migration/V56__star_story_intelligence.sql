-- Phase 7.15 — STAR Story Intelligence & Behavioral AI
-- Additive, idempotent (IF NOT EXISTS guards), never touches any existing table.
-- Applied by hand against Neon, same baseline-on-migrate caveat as V1/V4/V55.

CREATE TABLE IF NOT EXISTS star_stories (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    story_type            VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    source                VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    source_ref_id         UUID,
    situation             TEXT,
    task                  TEXT,
    action                TEXT,
    result                TEXT,
    reflection            TEXT,
    lessons_learned       TEXT,
    skills_used           TEXT,
    technologies_used     TEXT,
    competencies          TEXT,
    business_impact       TEXT,
    evidence              TEXT,
    confidence_score      INTEGER,
    quality_score         INTEGER,
    quality_breakdown     TEXT,
    improvement_suggestions TEXT,
    missing_sections      TEXT,
    current_version       INTEGER NOT NULL DEFAULT 1,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_star_stories_user ON star_stories (user_id);
CREATE INDEX IF NOT EXISTS idx_star_stories_user_type ON star_stories (user_id, story_type);
CREATE INDEX IF NOT EXISTS idx_star_stories_user_updated ON star_stories (user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS story_versions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    star_story_id   UUID NOT NULL,
    user_id         UUID NOT NULL,
    version         INTEGER NOT NULL,
    snapshot        TEXT,
    change_summary  TEXT,
    source          VARCHAR(32),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_story_versions_story ON story_versions (star_story_id, version DESC);
CREATE INDEX IF NOT EXISTS idx_story_versions_user ON story_versions (user_id);

CREATE TABLE IF NOT EXISTS story_usage (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    star_story_id   UUID NOT NULL,
    user_id         UUID NOT NULL,
    company_name    VARCHAR(255),
    target_role     VARCHAR(255),
    interview_round VARCHAR(128),
    question        TEXT,
    outcome         VARCHAR(64),
    used_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_story_usage_story ON story_usage (star_story_id);
CREATE INDEX IF NOT EXISTS idx_story_usage_user ON story_usage (user_id, used_at DESC);

CREATE TABLE IF NOT EXISTS story_recommendations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL,
    star_story_id   UUID NOT NULL,
    company_name    VARCHAR(255),
    target_role     VARCHAR(255),
    question        TEXT,
    match_score     INTEGER,
    reason          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_story_recs_user ON story_recommendations (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_story_recs_story ON story_recommendations (star_story_id);

CREATE TABLE IF NOT EXISTS story_analytics (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL UNIQUE,
    total_stories         INTEGER NOT NULL DEFAULT 0,
    avg_quality_score     INTEGER,
    by_category           TEXT,
    by_competency         TEXT,
    usage_count           INTEGER NOT NULL DEFAULT 0,
    recommendation_count  INTEGER NOT NULL DEFAULT 0,
    last_computed_at      TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
