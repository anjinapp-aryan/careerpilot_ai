# Workflow Definition Checklist

Fill this out **before** writing any code. Its purpose is to catch scope, naming, and duplication
problems while they're still cheap to fix — the same review Skill Gap Intelligence went through
before it was built (checking whether `learning.career.goal.SkillGapIntelligenceService` already
covered the need — it didn't, but the check mattered).

## 1. Business question

> One sentence: what does this workflow answer that nothing else in the platform already answers?

## 2. Duplication check

- [ ] Grepped the codebase for the proposed `<Workflow>` name and any close synonyms
- [ ] Confirmed no existing service/engine already computes this (list what you checked and why
      it's insufficient, e.g. "X computes Y from Z, but doesn't do W")
- [ ] If a near-duplicate exists, documented the distinction explicitly (see Skill Gap's own
      "Naming note" precedent for `SkillGapIntelligenceService` vs. this workflow)

## 3. Inputs

| Field | Source | Already exists? |
|---|---|---|
| e.g. `target_role` | `CareerMission.targetRole` | Yes |
| e.g. `resume_text` | (new — where does it come from?) | No — justify |

- [ ] Every input traces to a real, already-persisted entity, or is explicitly justified as new

## 4. Outputs

| Field | Computed by | Deterministic or AI? |
|---|---|---|
| e.g. `readiness_score` | formula over gap counts | Deterministic |
| e.g. `strengths` | LLM narrative | AI |

- [ ] For every AI-computed field, confirmed a deterministic computation genuinely isn't possible
      (never default to AI when a formula would do — see the Standard's "never invent a signal"
      discipline)

## 5. Agents

List each proposed LangGraph node, in execution order:

| # | Agent | Responsibility | AI call? |
|---|---|---|---|
| 1 | | | |
| 2 | | | |

- [ ] Checked each proposed node name against its own eventual state-key output for the
      LangGraph node/state-key collision rule (Standard §4)

## 6. Persistence

- [ ] One new table, one new `workflow_definition` seed row (per the Standard — do not propose a
      shared/generic result table unless one already exists)
- [ ] Confirmed no existing table already fits (if so, this workflow doesn't need its own)

## 7. Feature flags

- `<workflow>.workflow.enabled` — this workflow's own flag
- Confirms dependency on the shared `runtime.enabled` flag (already exists, not a new flag)

## 8. Non-goals

> What is this workflow explicitly NOT doing? (Mirrors Skill Gap's own "explicitly out of scope"
> discipline — e.g. no fabricated signal, no scope creep into an adjacent workflow's territory.)

## 9. Sign-off

- [ ] Reviewed against `docs/architecture/WORKFLOW_STANDARD.md` in full
- [ ] Reviewed `docs/adr/ADR-006/007/008` for the platform rationale
- [ ] Ready to proceed to `docs/workflows/java-workflow-template.md` / `python-workflow-template.md`
