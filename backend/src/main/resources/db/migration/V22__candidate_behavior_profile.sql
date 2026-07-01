-- CareerPilot AI — Phase 2C-5: candidate behavior profile (Step 7)
-- Additive only. A deterministic (AI-free) aggregation of the user's recommendation_feedback (2C-4):
-- roles/countries/work-modes/domains they tend to APPROVE/SAVE (preferred_*) vs REJECT/IGNORE
-- (rejected_*). One row per user, rebuilt on demand from the feedback log — no incremental drift.
-- Comma-joined lists, mirroring candidate_preferences. Consumed later for AI personalization.
--
-- Gated DARK by the same recommendation.feedback.enabled flag that produces the feedback it reads.
-- No existing table or flow is touched.
--
-- NOTE: same Neon hand-apply convention as V4–V21 (Flyway baselines; idempotent DDL).

CREATE TABLE IF NOT EXISTS candidate_behavior_profile (
    user_id               UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    preferred_roles       TEXT,
    rejected_roles        TEXT,
    preferred_countries   TEXT,
    rejected_countries    TEXT,
    preferred_work_modes  TEXT,
    rejected_work_modes   TEXT,
    preferred_domains     TEXT,
    rejected_domains      TEXT,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
