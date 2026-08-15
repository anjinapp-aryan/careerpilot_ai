-- International Job Discovery Phase 2 — a NEW, additive product-priority field, deliberately
-- separate from the existing `tier` column. `tier` already has live meaning elsewhere (badge
-- coloring, existing scoring lookups) and this phase's own audit found its current values don't
-- line up with the desired PRIMARY/PRIMARY_SPECIALIST/SECONDARY/SELECTIVE business hierarchy
-- (e.g. Ireland is TIER_2, Luxembourg is TIER_3) — repurposing it would silently change what
-- every existing `tier` reader means. `search_priority` is additive and nullable; `tier` is not
-- touched, renamed, or reinterpreted for any existing row.
ALTER TABLE supported_countries ADD COLUMN search_priority VARCHAR(20);

UPDATE supported_countries SET search_priority = 'PRIMARY' WHERE country_code IN ('de','ie','nl','gb');
UPDATE supported_countries SET search_priority = 'PRIMARY_SPECIALIST' WHERE country_code = 'lu';
UPDATE supported_countries SET search_priority = 'SECONDARY' WHERE country_code IN ('sg','ca','au');
UPDATE supported_countries SET search_priority = 'SELECTIVE' WHERE country_code = 'us';
-- ae (UAE) is intentionally left untouched (search_priority stays NULL) — it remains supported,
-- outside this 10-country priority strategy, exactly as required.

INSERT INTO supported_countries (country_code, display_name, tier, search_priority)
VALUES ('pl', 'Poland', 'TIER_2', 'SECONDARY');
