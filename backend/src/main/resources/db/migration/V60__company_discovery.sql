-- CareerPilot AI — Gap A: Company Discovery Agent
-- Additive only. Extends company_connectors (V59) with discovery-provenance columns so
-- auto-discovered ATS connectors (found by CompanyDiscoveryService probing keyless public
-- Greenhouse/Ashby/SmartRecruiters/Workday endpoints for candidate company slugs) can sit in the
-- same table as manually-admin-created connectors, distinguished only by discovery_status being
-- non-null. Manually-created connectors keep discovery_status = NULL (their existing rows are
-- untouched by this migration — ADD COLUMN IF NOT EXISTS, no backfill, no data change).

ALTER TABLE company_connectors ADD COLUMN IF NOT EXISTS discovery_status VARCHAR(32); -- PENDING_APPROVAL | APPROVED | REJECTED | NULL (manually admin-created)
ALTER TABLE company_connectors ADD COLUMN IF NOT EXISTS discovered_at TIMESTAMPTZ;
ALTER TABLE company_connectors ADD COLUMN IF NOT EXISTS discovered_by VARCHAR(64); -- e.g. "greenhouse-probe", "workday-probe"

CREATE INDEX IF NOT EXISTS idx_company_connectors_discovery_status ON company_connectors(discovery_status);
