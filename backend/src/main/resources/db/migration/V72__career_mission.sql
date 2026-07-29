-- Mission Engine, Phase 1. Mission is stable (career_mission), strategy is dynamic
-- (career_goal — milestones toward a mission), countries/industries/skills are data (JSONB lists,
-- never enums or FK-to-code). user_career_preference is deliberately NOT a duplicate of the
-- existing candidate_preferences table (V5/V9/V10 migrations) — it holds career-preference
-- dimensions candidate_preferences does not (risk tolerance, company-stage preference, work
-- culture, mentorship), not location/salary/visa data that already lives there.

CREATE TABLE career_mission (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    mission_statement TEXT NOT NULL,
    target_role VARCHAR(200) NOT NULL,
    target_level VARCHAR(100),
    target_industries JSONB,
    target_countries JSONB,
    salary_expectation_min NUMERIC(12,2),
    salary_expectation_max NUMERIC(12,2),
    salary_currency VARCHAR(10),
    timeline_months INTEGER,
    career_direction VARCHAR(200),
    skills_to_acquire JSONB,
    current_skills JSONB,
    career_ambition TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_career_mission_user ON career_mission (user_id);
CREATE INDEX idx_career_mission_user_status ON career_mission (user_id, status);

CREATE TABLE career_goal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    mission_id UUID NOT NULL REFERENCES career_mission(id) ON DELETE CASCADE,
    title VARCHAR(300) NOT NULL,
    description TEXT,
    target_date DATE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_career_goal_mission ON career_goal (mission_id);

CREATE TABLE user_career_preference (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE,
    risk_tolerance VARCHAR(20),
    preferred_company_stage VARCHAR(20),
    work_culture_priority VARCHAR(100),
    willing_to_relocate BOOLEAN NOT NULL DEFAULT true,
    open_to_contract_roles BOOLEAN NOT NULL DEFAULT false,
    mentorship_preference VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
