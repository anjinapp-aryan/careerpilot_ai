-- CareerPilot AI — Phase 7.13: Company Intelligence & Knowledge Graph
-- ADDITIVE ONLY. A per-user, domain-specific Company Knowledge Graph that grows every time the
-- platform discovers, researches, recommends, applies to, or learns an outcome about a company.
-- Knowledge is NEVER fabricated: every section/edge is written only from artifacts the platform
-- already computed (discovery rows, company research, interview prep, learning events, tailoring).
--
--   company_knowledge         — HEAD row per (user, normalized company): section map + 10 scores
--   company_knowledge_version — immutable append-only snapshot of every knowledge revision
--   company_timeline_event    — append-only company journey (discovered → applied → offer/reject …)
--   company_relationship      — graph edges (company → industry/technology/skill/role/…)
--
-- Same Neon hand-apply / idempotent-DDL convention as V4–V54. NOT applied here.

CREATE TABLE IF NOT EXISTS company_knowledge (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID NOT NULL,
    company_name          VARCHAR(255) NOT NULL,
    normalized_name       VARCHAR(255) NOT NULL,
    industry              VARCHAR(128),
    knowledge             TEXT,          -- JSON object: section key -> text content (see KnowledgeSection)
    quality_score         INTEGER,       -- all scores 0-100, deterministic + explainable
    interview_difficulty  INTEGER,
    hiring_probability    INTEGER,
    resume_compatibility  INTEGER,
    technology_match      INTEGER,
    career_growth         INTEGER,
    salary_potential      INTEGER,
    culture_match         INTEGER,
    learning_value        INTEGER,
    long_term_opportunity INTEGER,
    score_explanations    TEXT,          -- JSON object: score key -> human-readable reason
    knowledge_version     INTEGER NOT NULL DEFAULT 1,
    last_source           VARCHAR(48),   -- KnowledgeSource of the most recent update
    first_discovered_at   TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_knowledge_user_name UNIQUE (user_id, normalized_name)
);
CREATE INDEX IF NOT EXISTS idx_company_knowledge_user ON company_knowledge(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS company_knowledge_version (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_knowledge_id UUID NOT NULL REFERENCES company_knowledge(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL,
    version              INTEGER NOT NULL,
    knowledge            TEXT,           -- full section-map snapshot at this version
    changed_sections     TEXT,           -- comma-separated section keys changed in this revision
    source               VARCHAR(48),    -- KnowledgeSource that produced this revision
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_knowledge_version UNIQUE (company_knowledge_id, version)
);
CREATE INDEX IF NOT EXISTS idx_company_knowledge_version_head
    ON company_knowledge_version(company_knowledge_id, version DESC);

CREATE TABLE IF NOT EXISTS company_timeline_event (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_knowledge_id UUID NOT NULL REFERENCES company_knowledge(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL,
    event_type           VARCHAR(48) NOT NULL, -- DISCOVERED | RECOMMENDED | APPLICATION_SUBMITTED | ...
    ref_id               UUID,                 -- job/learning-event/package id behind the event
    detail               TEXT,
    occurred_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_company_timeline_company
    ON company_timeline_event(company_knowledge_id, occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_company_timeline_user ON company_timeline_event(user_id, occurred_at DESC);

CREATE TABLE IF NOT EXISTS company_relationship (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_knowledge_id UUID NOT NULL REFERENCES company_knowledge(id) ON DELETE CASCADE,
    user_id              UUID NOT NULL,
    relation_type        VARCHAR(32) NOT NULL, -- INDUSTRY | TECHNOLOGY | SKILL | ROLE | LOCATION | SALARY_BAND
    target               VARCHAR(255) NOT NULL,
    weight               INTEGER NOT NULL DEFAULT 1, -- observation count; strengthens on re-observation
    evidence             VARCHAR(64),                -- KnowledgeSource that last reinforced the edge
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_relationship UNIQUE (company_knowledge_id, relation_type, target)
);
CREATE INDEX IF NOT EXISTS idx_company_relationship_user
    ON company_relationship(user_id, relation_type, target);
