-- Phase: International Job Discovery Engine, Phase 1.
-- Parallel to job_recommendations (scoreV2's output), deliberately not merged into it — a second,
-- differently-weighted ranking formula stays structurally independent and independently droppable.
-- Written by InternationalJobRankingService, gated by career.international.ranking.enabled.
CREATE TABLE international_job_ranking (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    job_id UUID NOT NULL,
    rank_score INT NOT NULL,
    skill_score INT NOT NULL,
    visa_probability_score INT NOT NULL,
    salary_score INT NOT NULL,
    career_growth_score INT NOT NULL,
    company_stability_score INT NOT NULL,
    remote_flexibility_score INT NOT NULL,
    principal_engineer_growth_score INT NOT NULL,
    ai_tech_stack_score INT NOT NULL,
    country_code VARCHAR(2),
    tier VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, job_id)
);

CREATE INDEX idx_intl_ranking_user_score ON international_job_ranking (user_id, rank_score DESC);
