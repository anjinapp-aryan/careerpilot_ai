-- CareerPilot AI — Phase 7.15.1: Career Decision Memory. Additive only, ships DARK
-- (career.memory.enabled=false). Not applied by this work — hand-applied to Neon later,
-- same convention as V50-V62 (see CLAUDE.md's baseline-on-migrate note).
--
-- Append-only ledger of durable career decisions/preferences, extracted from existing
-- domain events (RecommendationFeedback, ApplicationRejected/Accepted, RecommendationApproved,
-- OfferReceived, InterviewDetected) rather than a new capture path — see CareerMemoryService.
-- Deliberately NOT the same shape as `learning_event` (V45): that table exists for the
-- pattern-computation engines (SuccessPatternEngine/FailurePatternEngine) and has no reason/
-- confidence/importance/expiry fields — this table exists for direct, ranked, human/AI-readable
-- retrieval by the Copilot, which needs those fields and `learning_event` was never shaped for.
--
-- No DB-level FK constraints for cross-aggregate references (job_id) — same convention as the
-- rest of this schema.

CREATE TABLE IF NOT EXISTS career_decision_memory (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL,

    decision_type     VARCHAR(64)  NOT NULL, -- e.g. JOB_REJECTED, JOB_APPROVED, JOB_SAVED, INTERVIEW_SCHEDULED, OFFER_RECEIVED
    category          VARCHAR(32)  NOT NULL, -- CAREER | TECHNOLOGY | COMPANY | COUNTRY | SALARY | INTERVIEW | LEARNING | NETWORKING | APPLICATION | PREFERENCE | BEHAVIOR | GOAL
    value             TEXT,                  -- the decided-upon thing itself (e.g. "Amazon", "Backend", "Germany")
    reason            TEXT,                  -- free-text why, when known (e.g. "relocation required") — never fabricated, null when not captured
    confidence        NUMERIC(3, 2) NOT NULL DEFAULT 1.00, -- 0.00-1.00; explicit user actions are 1.00, inferred ones lower
    source            VARCHAR(64)  NOT NULL, -- which existing subsystem/event produced this row (e.g. RECOMMENDATION_FEEDBACK, APPLICATION_LIFECYCLE)
    importance        SMALLINT     NOT NULL DEFAULT 3, -- 1 (low) - 5 (high); drives retrieval ranking alongside freshness
    ai_generated      BOOLEAN      NOT NULL DEFAULT true,  -- true = extracted automatically; false = explicit user confirmation
    user_confirmed    BOOLEAN      NOT NULL DEFAULT false, -- reserved for a future "yes, that's right" UI affordance — not set by this phase
    expires_at        TIMESTAMPTZ,           -- null = never expires; set for time-bound signals (e.g. "learning Kubernetes" fades after months of inactivity)

    job_id            UUID,
    correlation_id    UUID,
    workflow_id       VARCHAR(128),

    usage_count       INTEGER      NOT NULL DEFAULT 0,     -- incremented each time this memory is surfaced to the Copilot
    last_used_at      TIMESTAMPTZ,

    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
    -- Append-only by convention (see CareerMemoryService) — no updated_at; a superseding decision
    -- is a NEW row, never an UPDATE, so the timeline in review area 7 stays intact.
);

CREATE INDEX IF NOT EXISTS idx_career_decision_memory_user_category
    ON career_decision_memory(user_id, category, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_career_decision_memory_user_type
    ON career_decision_memory(user_id, decision_type, created_at DESC);
