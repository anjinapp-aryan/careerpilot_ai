-- Phase D — Employer Question Intelligence.
--
-- THE PROBLEM THIS SOLVES. Phase A found that the questions blocking a real submission were not
-- profile facts but employer-specific screening questions ("Are you subject to any post-employment
-- restrictions?", "Have you previously worked at this company?"). Phase C gave profile facts a home;
-- these have none, because the answer is prose the candidate must author once and then reuse.
--
-- TWO TABLES, NOT ONE. A question is a property of the employer's form; an answer is a property of
-- the candidate. Storing them together would force one row per (candidate x question) and make
-- "the same question, asked by three employers" impossible to recognise — which is precisely the
-- deduplication this phase exists to provide.
--
-- VENDOR-NEUTRAL. `ats_platform` and `employer` are recorded for reporting only. Nothing in the
-- matching path reads them: two employers asking the same question must resolve to the same
-- canonical question, so keying on employer would defeat reuse.
CREATE TABLE IF NOT EXISTS employer_question (
    id                  UUID PRIMARY KEY,

    -- Exactly as it appeared on the form. Kept verbatim so an operator can always see what was
    -- really asked rather than our interpretation of it.
    original_text       TEXT         NOT NULL,

    -- Deterministic normalisation of original_text (lowercased, punctuation and filler stripped).
    -- Unique: this is what collapses "What country do you currently live in?" and "Current country
    -- of residence" into one logical question.
    normalized_text     TEXT         NOT NULL UNIQUE,

    -- CanonicalField name when the question maps onto a profile fact, else SCREENING_QUESTION.
    canonical_field     VARCHAR(48)  NOT NULL,
    -- QuestionCategory name; the join key already used by AnswerResolver.
    question_category   VARCHAR(48)  NOT NULL,
    question_type       VARCHAR(24)  NOT NULL,
    required            BOOLEAN      NOT NULL DEFAULT FALSE,

    -- Match confidence of the canonical mapping, 0-100. Reporting only — it never gates an answer;
    -- approval does.
    confidence          INT          NOT NULL DEFAULT 0,

    -- Provenance of the sighting. Reporting only, never consulted when matching.
    employer            VARCHAR(200),
    ats_platform        VARCHAR(40),

    times_seen          INT          NOT NULL DEFAULT 1,
    first_seen_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_employer_question_category ON employer_question (question_category);
CREATE INDEX IF NOT EXISTS idx_employer_question_canonical ON employer_question (canonical_field);

-- One approved answer per (user, question). A candidate answers a question once; every later
-- employer asking it reuses that answer with no second review.
CREATE TABLE IF NOT EXISTS employer_answer (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL,
    question_id         UUID         NOT NULL,

    answer_text         TEXT,

    -- AnswerConfidence name. Only the trusted bands may reach browser automation; AI_SUGGESTED
    -- never does, however plausible the draft looks.
    confidence          VARCHAR(24)  NOT NULL,

    -- A draft is NOT usable. Approval is an explicit human act, recorded with who and when so the
    -- decision is auditable long after it was made.
    approved            BOOLEAN      NOT NULL DEFAULT FALSE,
    approved_by         UUID,
    approved_at         TIMESTAMPTZ,

    -- Why this answer was selected, rendered for explainability.
    source              VARCHAR(200),

    usage_count         INT          NOT NULL DEFAULT 0,
    last_used_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_employer_answer_user_question UNIQUE (user_id, question_id)
);

CREATE INDEX IF NOT EXISTS idx_employer_answer_user ON employer_answer (user_id);
CREATE INDEX IF NOT EXISTS idx_employer_answer_pending ON employer_answer (user_id, approved);
