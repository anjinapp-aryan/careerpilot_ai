-- Phase: International Job Discovery Engine, Phase 1.
-- Config-driven country tier system for the international relocation program. This is
-- deliberately a small, admin-editable-later reference table (not application.yml) so future
-- countries (USA, Singapore, Japan, ...) are a data-only row insert, never a code change.
-- Gated by career.international.tiering.enabled (default false) — inert until that flag is on.
CREATE TABLE supported_countries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    country_code VARCHAR(2) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    tier VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO supported_countries (country_code, display_name, tier) VALUES
    ('de', 'Germany', 'TIER_1'),
    ('nl', 'Netherlands', 'TIER_1'),
    ('ie', 'Ireland', 'TIER_2'),
    ('ca', 'Canada', 'TIER_2'),
    ('au', 'Australia', 'TIER_2'),
    ('lu', 'Luxembourg', 'TIER_3');
