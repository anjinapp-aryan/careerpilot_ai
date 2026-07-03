-- CareerPilot AI — Phase 2A: Enterprise Job Discovery Platform (Job Lake)
-- Additive only, idempotent. Builds a SHADOW ingestion pipeline alongside the existing
-- `jobs`-table discovery (JobAggregationService) — it does NOT touch `jobs`, `job_recommendations`,
-- or `job_fetch_audit`, so every existing reader (Recommended/Domestic/International/Browse)
-- is byte-for-byte unaffected. The entire lake path is gated DARK behind JOB_DISCOVERY_ENABLED=false;
-- no rows are written here until that flag is explicitly flipped on.
--
-- Layering:  job_raw → job_normalized → job_lake  (canonical, status machine),
--            with job_duplicate_mapping (dedup) and job_discovery_audit/_failure (observability).
--
-- NOTE: same Neon hand-apply convention as V4–V15 (Flyway baselines; this DDL is idempotent so it
-- applies cleanly by hand against DATABASE_URL_PY and also on a fresh DB).

-- ── Raw provider payloads — never discarded (Step 4) ────────────────────────────
CREATE TABLE IF NOT EXISTS job_raw (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider     VARCHAR(60)  NOT NULL,
    external_id  VARCHAR(255) NOT NULL,
    payload_json JSONB        NOT NULL,
    checksum     VARCHAR(64)  NOT NULL,           -- sha-256 of the raw payload; idempotent re-ingest
    run_id       UUID,                            -- the discovery run that captured this row
    fetched_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_raw_provider_external_checksum UNIQUE (provider, external_id, checksum)
);
CREATE INDEX IF NOT EXISTS idx_job_raw_provider_external ON job_raw(provider, external_id);
CREATE INDEX IF NOT EXISTS idx_job_raw_run ON job_raw(run_id);

-- ── Normalized layer — one schema across all providers (Step 5) ─────────────────
CREATE TABLE IF NOT EXISTS job_normalized (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    raw_id          UUID REFERENCES job_raw(id) ON DELETE SET NULL,
    provider        VARCHAR(60)  NOT NULL,
    external_job_id VARCHAR(255) NOT NULL,
    title           VARCHAR(500),
    company         VARCHAR(300),
    country         VARCHAR(80),
    city            VARCHAR(120),
    location_text   VARCHAR(500),
    description     TEXT,
    apply_url       VARCHAR(1000),
    salary_min      NUMERIC,
    salary_max      NUMERIC,
    remote          BOOLEAN,
    visa_supported  BOOLEAN,
    posted_date     TIMESTAMPTZ,
    employment_type VARCHAR(60),
    source_url      VARCHAR(1000),
    checksum        VARCHAR(64),                  -- sha-256 of normalized identity fields
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_normalized_provider_external UNIQUE (provider, external_job_id)
);
CREATE INDEX IF NOT EXISTS idx_job_normalized_country ON job_normalized(country);

-- ── Canonical Job Lake — status lifecycle (Step 7) ──────────────────────────────
CREATE TABLE IF NOT EXISTS job_lake (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    normalized_job_id  UUID NOT NULL REFERENCES job_normalized(id) ON DELETE CASCADE,
    status             VARCHAR(20) NOT NULL DEFAULT 'DISCOVERED', -- DISCOVERED|NORMALIZED|DEDUPLICATED|READY_FOR_AI
    source_count       INT NOT NULL DEFAULT 1,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_lake_normalized UNIQUE (normalized_job_id)
);
CREATE INDEX IF NOT EXISTS idx_job_lake_status ON job_lake(status);

-- ── Deduplication mapping — source job → canonical job (Step 6) ─────────────────
CREATE TABLE IF NOT EXISTS job_duplicate_mapping (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_job    UUID NOT NULL REFERENCES job_normalized(id) ON DELETE CASCADE,
    canonical_job UUID NOT NULL REFERENCES job_normalized(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_job_duplicate_mapping_source UNIQUE (source_job)
);
CREATE INDEX IF NOT EXISTS idx_job_duplicate_mapping_canonical ON job_duplicate_mapping(canonical_job);

-- ── Run-level audit (Step 10) ───────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS job_discovery_audit (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id        UUID,
    provider      VARCHAR(60) NOT NULL,
    jobs_found    INT NOT NULL DEFAULT 0,
    jobs_inserted INT NOT NULL DEFAULT 0,
    duplicates    INT NOT NULL DEFAULT 0,
    failures      INT NOT NULL DEFAULT 0,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_job_discovery_audit_run ON job_discovery_audit(run_id);
CREATE INDEX IF NOT EXISTS idx_job_discovery_audit_provider ON job_discovery_audit(provider, started_at DESC);

CREATE TABLE IF NOT EXISTS job_discovery_failure (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id     UUID,
    provider   VARCHAR(60) NOT NULL,
    error      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_job_discovery_failure_run ON job_discovery_failure(run_id);
