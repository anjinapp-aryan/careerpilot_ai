-- CareerPilot AI — AI Copilot conversation memory
-- Persists ChatGPT-style conversations and their streamed messages so the
-- Copilot has durable, page-aware history per user (multi-tenant via org_id).

CREATE TABLE copilot_conversations (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id          UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    page            VARCHAR(40),                 -- resume | jobs | applications | workflow | dashboard
    title           VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_copilot_conv_user ON copilot_conversations(user_id, updated_at DESC);

CREATE TABLE copilot_messages (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES copilot_conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,        -- USER | ASSISTANT | SYSTEM
    content         TEXT NOT NULL,
    action          VARCHAR(60),                 -- improve_resume | ats_analysis | job_matching | ...
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_copilot_msg_conv ON copilot_messages(conversation_id, created_at);
