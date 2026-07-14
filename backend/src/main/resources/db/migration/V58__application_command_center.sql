-- Phase: Applications Page -> AI Job Application Command Center.
-- Additive-only columns on the existing `applications` table (same hand-apply-to-Neon convention
-- as V50-V57 — see CLAUDE.md). No existing column is touched, renamed, or dropped.
--
-- favorite  — user-starred applications, surfaced first in the command-center card grid.
-- priority  — LOW|MEDIUM|HIGH, user-settable triage priority (independent of the computed
--             ApplicationHealthService bucket, which is derived read-time and not persisted).
-- archived  — soft "hide from board" flag for the bulk-archive action; does not change `status`.

ALTER TABLE applications ADD COLUMN IF NOT EXISTS favorite boolean NOT NULL DEFAULT false;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS priority text NOT NULL DEFAULT 'MEDIUM';
ALTER TABLE applications ADD COLUMN IF NOT EXISTS archived boolean NOT NULL DEFAULT false;
