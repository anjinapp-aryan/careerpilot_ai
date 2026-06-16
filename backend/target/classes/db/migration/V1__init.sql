-- CareerPilot AI — initial schema
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

-- ============ Tenancy ============
CREATE TABLE organizations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    slug            VARCHAR(80)  NOT NULL UNIQUE,
    plan            VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    full_name       VARCHAR(200),
    role            VARCHAR(20)  NOT NULL DEFAULT 'USER', -- USER | ADMIN | OWNER
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    email_verified  BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at   TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_users_org ON users(org_id);

-- ============ Subscription / Billing ============
CREATE TABLE subscriptions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    plan            VARCHAR(20)  NOT NULL,            -- FREE | PRO | ENTERPRISE
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    current_period_start TIMESTAMPTZ,
    current_period_end   TIMESTAMPTZ,
    seats           INT          NOT NULL DEFAULT 1,
    provider        VARCHAR(40),
    provider_ref    VARCHAR(120),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_subscriptions_org ON subscriptions(org_id);

CREATE TABLE usage_records (
    id              BIGSERIAL PRIMARY KEY,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id         UUID REFERENCES users(id) ON DELETE SET NULL,
    feature         VARCHAR(60)  NOT NULL,
    units           INT          NOT NULL DEFAULT 1,
    cost_usd        NUMERIC(12,6) NOT NULL DEFAULT 0,
    metadata        JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_usage_org_time ON usage_records(org_id, created_at DESC);

-- ============ Resume ============
CREATE TABLE resumes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    filename        VARCHAR(255) NOT NULL,
    s3_key          VARCHAR(500) NOT NULL,
    content_type    VARCHAR(120),
    size_bytes      BIGINT,
    parsed_text     TEXT,
    candidate_profile JSONB,
    extracted_skills JSONB,
    resume_score    INT,
    embedding       vector(768),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_resumes_user ON resumes(user_id);
CREATE INDEX idx_resumes_org  ON resumes(org_id);

-- ============ Jobs ============
CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID REFERENCES organizations(id) ON DELETE CASCADE,
    title           VARCHAR(255) NOT NULL,
    company         VARCHAR(255) NOT NULL,
    location        VARCHAR(255),
    description     TEXT NOT NULL,
    salary_range    VARCHAR(120),
    source          VARCHAR(60),
    external_url    VARCHAR(1000),
    embedding       vector(768),
    posted_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_jobs_org ON jobs(org_id);
CREATE INDEX idx_jobs_company ON jobs(company);

-- ============ Applications ============
CREATE TABLE applications (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    resume_id       UUID REFERENCES resumes(id) ON DELETE SET NULL,
    status          VARCHAR(30)  NOT NULL DEFAULT 'SAVED', -- SAVED|APPLIED|INTERVIEWING|OFFER|REJECTED|WITHDRAWN
    match_score     INT,
    ats_score       INT,
    next_action     TEXT,
    next_action_at  TIMESTAMPTZ,
    notes           TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_applications_user ON applications(user_id);
CREATE INDEX idx_applications_status ON applications(user_id, status);

-- ============ Workflow runs ============
CREATE TABLE workflow_runs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    thread_id       VARCHAR(64) NOT NULL UNIQUE,
    status          VARCHAR(20) NOT NULL,  -- RUNNING | INTERRUPTED | COMPLETED | ERROR
    target_role     VARCHAR(200),
    target_seniority VARCHAR(60),
    resume_score    INT,
    job_match_score INT,
    ats_score       INT,
    interview_readiness_score INT,
    state           JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_wfruns_user ON workflow_runs(user_id, created_at DESC);

-- ============ Audit log ============
CREATE TABLE audit_logs (
    id              BIGSERIAL PRIMARY KEY,
    org_id          UUID,
    user_id         UUID,
    actor_email     VARCHAR(255),
    action          VARCHAR(80) NOT NULL,
    target_type     VARCHAR(80),
    target_id       VARCHAR(120),
    ip              VARCHAR(64),
    user_agent      VARCHAR(500),
    metadata        JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_org_time ON audit_logs(org_id, created_at DESC);

-- ============ Refresh tokens ============
CREATE TABLE refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(128) NOT NULL UNIQUE,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_user ON refresh_tokens(user_id);
