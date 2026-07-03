-- CareerPilot AI — Phase 2C-1/2C-2: priority + category refinement on job_recommendations
-- Additive only. All nullable, all recomputed on the next refreshForUser (like `category` in V18),
-- so existing behavior is unchanged and existing rows self-heal on their next refresh.
--
--  * priority        — CRITICAL|HIGH|MEDIUM|LOW, from PriorityEngine (2C-1). Ranking metadata only;
--                      does NOT change the existing match_score ordering of the Recommended tab.
--  * priority_score  — the raw additive priority number (base score + bonuses), for display/observability.
--  * must_apply      — BOOLEAN, from MustApplyEvaluator (2C-2). A strict AND of role/preference/visa/
--                      recency; a cross-cutting flag, not a category value.
--
-- Also refines the 2C-1(V18) category vocabulary in place (2C-2): AUTO_APPLY→AUTO_APPLY_READY,
-- GOOD_MATCH→RECOMMENDED, IGNORE→ARCHIVED (HUMAN_REVIEW keeps its name). Nothing parses category back
-- into an enum, and the values self-recompute on the next refresh — this remap just makes already-stored
-- rows consistent immediately instead of after each user's next refresh. Idempotent.
--
-- NOTE: same Neon hand-apply convention as V4–V19 (Flyway baselines; idempotent DDL/DML).

ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS priority       VARCHAR(20);
ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS priority_score INT;
ALTER TABLE job_recommendations ADD COLUMN IF NOT EXISTS must_apply     BOOLEAN;

-- 2C-2 category vocabulary remap (name changes only; threshold-shift reclassification self-heals on refresh).
UPDATE job_recommendations SET category = 'AUTO_APPLY_READY' WHERE category = 'AUTO_APPLY';
UPDATE job_recommendations SET category = 'RECOMMENDED'      WHERE category = 'GOOD_MATCH';
UPDATE job_recommendations SET category = 'ARCHIVED'         WHERE category = 'IGNORE';

-- 2C-3: capture the human decision alongside the existing scoring-breakdown audit (reuses V15's table
-- instead of a new recommendation_decision_audit — one audit surface, no split-brain). Nullable.
ALTER TABLE recommendation_audit ADD COLUMN IF NOT EXISTS decision VARCHAR(20);
