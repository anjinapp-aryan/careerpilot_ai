-- CareerPilot AI — Phase 5.3.1: Enterprise ATS Connector Framework refinement
-- Additive only. Persists the company-connector configuration that previously lived only in CSV
-- env vars (career.discovery.{workday,taleo,successfactors}.companies) so it can be
-- viewed/enabled/disabled/synced from an admin UI. The CSV path keeps working unchanged —
-- CompanyConnectorService bootstraps this table from CSV on first read if it's empty, it never
-- replaces the CSV as the deploy-time source of truth.

CREATE TABLE IF NOT EXISTS company_connectors (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    company_name          VARCHAR(255) NOT NULL,
    ats_type              VARCHAR(32)  NOT NULL,  -- WORKDAY | TALEO | SUCCESSFACTORS
    career_url            TEXT,
    tenant                VARCHAR(255),            -- Workday-only: tenant slug (e.g. "nvidia")
    cluster               VARCHAR(32),             -- Workday-only: data-center cluster (e.g. "wd5")
    site                  VARCHAR(255),             -- Workday-only: career-site id
    country               VARCHAR(8),
    industry              VARCHAR(128),
    priority              INTEGER NOT NULL DEFAULT 0,
    enabled               BOOLEAN NOT NULL DEFAULT true,
    health_status         VARCHAR(16) NOT NULL DEFAULT 'NEVER_RUN', -- UP|DEGRADED|DOWN|NEVER_RUN|NOT_CONFIGURED
    last_successful_sync  TIMESTAMPTZ,
    last_failure          TIMESTAMPTZ,
    last_failure_reason   TEXT,
    failure_count         INTEGER NOT NULL DEFAULT 0,
    average_latency_ms    BIGINT NOT NULL DEFAULT 0,
    jobs_imported         INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_connectors_ats_tenant UNIQUE (ats_type, company_name)
);
CREATE INDEX IF NOT EXISTS idx_company_connectors_ats_type ON company_connectors(ats_type);
CREATE INDEX IF NOT EXISTS idx_company_connectors_enabled ON company_connectors(enabled);
