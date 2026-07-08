-- CareerPilot AI — Phase 7.11: Application Package Intelligence (schema extension)
-- ADDITIVE ONLY. Extends the existing Phase 2D.6 `application_package` HEAD row into the canonical
-- pre-submission source of truth by recording the enrichment lineage the intelligence layer binds:
--   the bound autonomous Application Decision, the Company Research snapshot (transient — stored as a
--   boolean + short text since CompanyResearchEngine has no table), the Phase 6.5 learning boost,
--   the recommendation strength, a human-readable resume match summary, the workflow correlation id,
--   and the latest validation verdict (READY | HUMAN_REVIEW | BLOCKED).
-- The 2D.6 assembler (ApplicationPackageService) is NOT changed — these columns are populated only by
-- the Phase 7.11 ApplicationPackageIntelligenceService, and stay NULL for stock 2D.6 packages.
-- Same Neon hand-apply / idempotent-DDL convention as V4-V51. NOT applied here — enabling is a
-- separate, later human decision.

ALTER TABLE application_package
    ADD COLUMN IF NOT EXISTS application_decision_id     UUID,
    ADD COLUMN IF NOT EXISTS company_research_available  BOOLEAN,
    ADD COLUMN IF NOT EXISTS company_research_summary    TEXT,
    ADD COLUMN IF NOT EXISTS learning_boost              INTEGER,
    ADD COLUMN IF NOT EXISTS recommendation_strength     VARCHAR(16),
    ADD COLUMN IF NOT EXISTS match_summary               TEXT,
    ADD COLUMN IF NOT EXISTS correlation_id              UUID,
    ADD COLUMN IF NOT EXISTS validation_status           VARCHAR(16); -- READY | HUMAN_REVIEW | BLOCKED

CREATE INDEX IF NOT EXISTS idx_application_package_application
    ON application_package(application_id);
