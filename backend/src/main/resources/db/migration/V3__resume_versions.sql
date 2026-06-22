-- CareerPilot AI — Resume Optimization version history (Stage 1)
-- Each AI optimization of a resume appends an immutable version row. The original
-- `resumes` row is never overwritten. The generated optimized document is stored in
-- S3/MinIO (s3_key); optimized_text retains the text so the UI and TXT download work
-- even if binary (DOCX) rendering failed.

CREATE TABLE resume_versions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    resume_id          UUID NOT NULL REFERENCES resumes(id) ON DELETE CASCADE,
    user_id            UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id             UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    version_number     INT NOT NULL,
    optimization_mode  VARCHAR(60),
    ats_before         INT,
    ats_after          INT,
    provider_used      VARCHAR(40),
    workflow_thread_id VARCHAR(64),
    s3_key             VARCHAR(500),
    content_type       VARCHAR(120),
    optimized_text     TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_resume_versions_resume ON resume_versions(resume_id, version_number DESC);
CREATE INDEX idx_resume_versions_user ON resume_versions(user_id);
