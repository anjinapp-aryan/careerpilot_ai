-- CareerPilot AI — Phase 3A.4: Interview Tracking Engine
-- Additive only, ships DARK. NOT applied by this work.
--
-- Tracks interview rounds per application (Recruiter/Technical/System Design/Manager/HR/Final/Offer),
-- with append-only feedback and a per-round event timeline.

CREATE TABLE IF NOT EXISTS interview (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID NOT NULL,
    job_id           UUID NOT NULL,
    interview_type   VARCHAR(32) NOT NULL,
    interviewer      VARCHAR(255),
    duration_minutes INT,
    result           VARCHAR(32),   -- SCHEDULED|PASSED|FAILED|PENDING|CANCELLED
    scheduled_at     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_interview_user_job
    ON interview(user_id, job_id, created_at DESC);

CREATE TABLE IF NOT EXISTS interview_feedback (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_id UUID NOT NULL REFERENCES interview(id) ON DELETE CASCADE,
    feedback     TEXT,
    rating       INT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_interview_feedback_interview
    ON interview_feedback(interview_id);

CREATE TABLE IF NOT EXISTS interview_timeline (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    interview_id UUID NOT NULL REFERENCES interview(id) ON DELETE CASCADE,
    event_type   VARCHAR(64) NOT NULL,
    details      TEXT,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_interview_timeline_interview
    ON interview_timeline(interview_id, occurred_at DESC);
