-- International Job Discovery Phase 2 — additive columns on the existing country_intelligence
-- row (same one-source-of-truth convention V73 already established: extend the row, never a new
-- country_profile-style table). Nullable: absence means "not curated" rather than a fabricated
-- neutral value. Curated heuristics, not live/authoritative immigration or labor-market data —
-- same caveat as every existing curated column on this table (see V70's header comment).
ALTER TABLE country_intelligence ADD COLUMN language_friendly_score INT;
ALTER TABLE country_intelligence ADD COLUMN industry_fit JSONB;

UPDATE country_intelligence SET language_friendly_score = 75, industry_fit = '["BANKING","ENTERPRISE","CLOUD","PLATFORM"]' WHERE country_code = 'de';
UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["FINTECH","BANKING","CLOUD"]' WHERE country_code = 'ie';
UPDATE country_intelligence SET language_friendly_score = 85, industry_fit = '["FINTECH","ENTERPRISE","PLATFORM"]' WHERE country_code = 'nl';
UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["FINTECH","BANKING","ENTERPRISE"]' WHERE country_code = 'gb';
UPDATE country_intelligence SET language_friendly_score = 80, industry_fit = '["BANKING","FINTECH"]' WHERE country_code = 'lu';
-- Poland has no existing country_intelligence row (V70/V86 never seeded it) — insert one now
-- that Poland is a supported country (V89), curated on the same 0-100 scale as every other row.
INSERT INTO country_intelligence (country_code, visa_probability_score, relocation_difficulty_score,
    language_requirement_score, cost_of_living_index, expected_savings_score, job_stability_score,
    tech_market_score, principal_engineer_growth_score, ai_market_score, source_note,
    visa_information, salary_information, technology_demand, remote_policy, priority_score,
    language_friendly_score, industry_fit) VALUES
    ('pl', 62, 35, 45, 45, 58, 76, 78, 62, 68,
     'EU Blue Card / national work permit curated baseline',
     'EU Blue Card or standard employer-sponsored work permit — established Java/banking-tech hiring corridor (Kraków/Warsaw).',
     'Principal/Staff engineers typically PLN 25,000-38,000/month base.',
     '["Java", "Spring", "Kafka", "AWS", "Kubernetes", "Banking"]',
     'Hybrid standard at enterprise/banking tech hubs',
     74, 70, '["BANKING","PLATFORM","CLOUD"]');

UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["FINTECH","BANKING","CLOUD"]' WHERE country_code = 'sg';
UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["ENTERPRISE","CLOUD"]' WHERE country_code = 'ca';
UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["ENTERPRISE","CLOUD"]' WHERE country_code = 'au';
UPDATE country_intelligence SET language_friendly_score = 100, industry_fit = '["FINTECH","ENTERPRISE","CLOUD"]' WHERE country_code = 'us';
-- ae (UAE) intentionally left NULL — outside this phase's curated scope, not fabricated.
