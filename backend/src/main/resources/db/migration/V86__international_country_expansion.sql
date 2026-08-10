-- Global Job Discovery Expansion — adds UK/USA/Singapore/UAE to the Phase 1 program.
-- Purely additive, per the International Job Discovery Engine Phase 1 migration's own stated
-- design ("future countries are a data-only row insert, never a code change"). No existing
-- supported_countries/country_intelligence row is modified, renamed, or removed.
INSERT INTO supported_countries (country_code, display_name, tier) VALUES
    ('gb', 'United Kingdom', 'TIER_1'),
    ('us', 'United States', 'TIER_1'),
    ('sg', 'Singapore', 'TIER_2'),
    ('ae', 'United Arab Emirates', 'TIER_2');

-- Curated, static country intelligence — same discipline and caveat as the Phase 1/Phase 2 seed
-- data already in the table: placeholder-but-defensible 0-100 indices with provenance in
-- source_note, not live-computed, should be reviewed against real published indices before being
-- relied on for a real decision.
INSERT INTO country_intelligence (country_code, visa_probability_score, relocation_difficulty_score,
    language_requirement_score, cost_of_living_index, expected_savings_score, job_stability_score,
    tech_market_score, principal_engineer_growth_score, ai_market_score, source_note,
    visa_information, salary_information, technology_demand, remote_policy, priority_score) VALUES
    ('gb', 65, 45, 15, 75, 60, 78, 88, 74, 82,
     'Skilled Worker visa curated baseline',
     'Skilled Worker visa — employer-sponsored, requires a licensed sponsor and a job on the shortage/eligible occupation list.',
     'Principal/Staff engineers typically GBP 90,000-140,000 base in London.',
     '["Java", "Spring Boot", "Cloud", "Fintech", "AWS"]',
     'Hybrid standard across enterprise and fintech employers',
     84),
    ('us', 40, 65, 10, 70, 72, 74, 95, 85, 92,
     'H-1B lottery-gated curated baseline — the US is NOT an ordinary relocation market; sponsorship is uncertain by default',
     'H-1B (annual lottery cap, uncertain outcome) or L-1 intra-company transfer (no lottery, requires existing employment at a multinational with a US entity) — treat as low-probability unless the employer has a documented sponsorship history.',
     'Principal/Staff engineers typically USD 180,000-260,000 base in major tech hubs.',
     '["Java", "Cloud", "AI", "Distributed Systems", "Kubernetes"]',
     'Widespread remote/hybrid in tech, though sponsorship-eligible roles skew on-site',
     70),
    ('sg', 58, 50, 25, 66, 62, 80, 84, 70, 80,
     'Employment Pass (EP) curated baseline — points-based (COMPASS framework), salary-threshold gated',
     'Employment Pass — employer-sponsored, points-based under the COMPASS framework, no direct residency track.',
     'Principal/Staff engineers typically SGD 150,000-220,000 base.',
     '["Java", "Cloud", "Fintech", "Spring Boot", "AWS"]',
     'Hybrid common in fintech/enterprise tech',
     72),
    ('ae', 55, 35, 20, 58, 70, 68, 76, 60, 74,
     'Golden Visa / employer-sponsored work visa curated baseline — realistic relocation backup, not a primary target',
     'Employer-sponsored work visa (standard) or Golden Visa (10-year, criteria-based) — no personal income tax, but treat as a backup rather than a primary relocation market.',
     'Principal/Staff engineers typically AED 30,000-45,000/month (tax-free) in Dubai/Abu Dhabi.',
     '["Java", "Cloud", "Enterprise Platforms", "AWS"]',
     'On-site/hybrid more common than remote-first',
     58);
