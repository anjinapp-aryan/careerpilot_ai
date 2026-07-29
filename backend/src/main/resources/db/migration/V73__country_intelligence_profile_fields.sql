-- Country Intelligence Engine, Phase 2. Extends the existing country_intelligence table
-- (International Job Discovery Engine, Phase 1) rather than creating a parallel country_profile
-- table — same country data, one source of truth. The 9 pre-existing 0-100 score columns are
-- untouched; these 5 new columns are the richer, descriptive fields this phase adds.
ALTER TABLE country_intelligence
    ADD COLUMN visa_information TEXT,
    ADD COLUMN salary_information TEXT,
    ADD COLUMN technology_demand JSONB,
    ADD COLUMN remote_policy VARCHAR(100),
    ADD COLUMN priority_score INT;

UPDATE country_intelligence SET
    visa_information = 'EU Blue Card — employer-sponsored, fast-tracked for shortage occupations including IT.',
    salary_information = 'Principal/Staff engineers typically EUR 90,000-140,000 base in major tech hubs (Berlin, Munich).',
    technology_demand = '["Java", "Spring Boot", "Cloud", "AI"]',
    remote_policy = 'Hybrid-friendly, remote-first roles increasingly common in tech',
    priority_score = 82
WHERE country_code = 'de';

UPDATE country_intelligence SET
    visa_information = 'Highly Skilled Migrant visa — employer-sponsored, salary-threshold based, no labor market test.',
    salary_information = 'Principal/Staff engineers typically EUR 85,000-130,000 base in Amsterdam/Eindhoven.',
    technology_demand = '["Java", "Cloud", "Microservices"]',
    remote_policy = 'Strong remote/hybrid culture across the tech sector',
    priority_score = 85
WHERE country_code = 'nl';

UPDATE country_intelligence SET
    visa_information = 'Critical Skills Employment Permit — fast-tracked, immediate family reunification, path to Stamp 4.',
    salary_information = 'Principal/Staff engineers typically EUR 80,000-125,000 base in Dublin.',
    technology_demand = '["Java", "Cloud", "AI", "Data Engineering"]',
    remote_policy = 'Hybrid standard at most multinational tech employers based in Dublin',
    priority_score = 80
WHERE country_code = 'ie';

UPDATE country_intelligence SET
    visa_information = 'Express Entry (Federal Skilled Worker) — points-based, permanent residency track from day one.',
    salary_information = 'Principal/Staff engineers typically CAD 130,000-190,000 base in Toronto/Vancouver.',
    technology_demand = '["Java", "Cloud", "AI", "Microservices"]',
    remote_policy = 'Widespread hybrid and remote-first postings',
    priority_score = 83
WHERE country_code = 'ca';

UPDATE country_intelligence SET
    visa_information = 'Skilled Independent (subclass 189) or Employer Sponsored (subclass 482) — permanent residency track.',
    salary_information = 'Principal/Staff engineers typically AUD 160,000-230,000 base in Sydney/Melbourne.',
    technology_demand = '["Java", "Cloud", "AI"]',
    remote_policy = 'Hybrid common; distance from other tech hubs limits fully-remote-abroad demand',
    priority_score = 76
WHERE country_code = 'au';

UPDATE country_intelligence SET
    visa_information = 'EU Blue Card — employer-sponsored; smallest labor market of the 6 Phase-1 countries.',
    salary_information = 'Principal/Staff engineers typically EUR 95,000-150,000 base — high pay, small market.',
    technology_demand = '["Java", "Cloud", "Finance Tech"]',
    remote_policy = 'Financial-sector-heavy market; on-site/hybrid more common than remote-first',
    priority_score = 62
WHERE country_code = 'lu';
