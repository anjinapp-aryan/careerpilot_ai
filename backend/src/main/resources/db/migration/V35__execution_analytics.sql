-- CareerPilot AI — Phase 2E.8: Execution Analytics
-- HIGH RISK PHASE. Additive only, ships DARK. NOT applied by this work.
--
-- execution_analytics stores per-user metric rollups computed from execution + tracking events:
-- submitted, success/failure rate, ATS success, interview/offer rate, provider/browser latency,
-- application duration. Append-only snapshots keyed by (user, metric, window_start).
--
-- NOTE: same Neon hand-apply convention as V4-V34 (idempotent DDL).

CREATE TABLE IF NOT EXISTS execution_analytics (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID NOT NULL,
    metric        VARCHAR(48) NOT NULL, -- e.g. applications_submitted | success_rate | interview_rate | provider_latency_ms
    value         NUMERIC(14,4) NOT NULL,
    window_start  TIMESTAMPTZ,
    computed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_exec_analytics_user_metric
    ON execution_analytics(user_id, metric, computed_at DESC);
