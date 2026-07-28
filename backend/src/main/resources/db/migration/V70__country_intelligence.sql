-- Phase: International Job Discovery Engine, Phase 1.
-- Static, curated reference data — NOT live-computed. There is no real data source for e.g. live
-- visa-approval probability, so this ships as curated 0-100 indices with provenance in
-- source_note, seeded for the 6 Phase 1 countries. Values are placeholder-but-defensible and
-- should be reviewed against real published indices (OECD/MPI skilled-migration reports, Numbeo
-- cost of living) before they're relied on for a real user-facing decision; the schema and
-- seeding mechanism are final regardless. Gated by career.international.ranking.enabled.
CREATE TABLE country_intelligence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code VARCHAR(2) NOT NULL UNIQUE REFERENCES supported_countries(country_code),
    visa_probability_score INT NOT NULL,
    relocation_difficulty_score INT NOT NULL,
    language_requirement_score INT NOT NULL,
    cost_of_living_index INT NOT NULL,
    expected_savings_score INT NOT NULL,
    job_stability_score INT NOT NULL,
    tech_market_score INT NOT NULL,
    principal_engineer_growth_score INT NOT NULL,
    ai_market_score INT NOT NULL,
    source_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO country_intelligence (country_code, visa_probability_score, relocation_difficulty_score,
    language_requirement_score, cost_of_living_index, expected_savings_score, job_stability_score,
    tech_market_score, principal_engineer_growth_score, ai_market_score, source_note) VALUES
    ('de', 75, 55, 60, 62, 65, 80, 85, 75, 78, 'Skilled Worker/EU Blue Card curated baseline'),
    ('nl', 80, 45, 35, 68, 68, 82, 78, 72, 74, 'Highly Skilled Migrant curated baseline'),
    ('ie', 70, 40, 10, 78, 60, 78, 80, 68, 76, 'Critical Skills Employment Permit curated baseline'),
    ('ca', 78, 40, 10, 65, 66, 80, 76, 70, 72, 'Express Entry curated baseline'),
    ('au', 68, 50, 10, 72, 64, 76, 74, 66, 70, 'Skilled Independent/Employer Sponsored curated baseline'),
    ('lu', 62, 60, 55, 88, 72, 84, 60, 65, 58, 'EU Blue Card curated baseline, small market');
