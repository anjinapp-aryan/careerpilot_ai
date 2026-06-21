# Repository Memory Maintenance Workflow

This document defines when and how to update CareerPilot AI's four memory files. It exists
because [docs/PROJECT_MEMORY.md](PROJECT_MEMORY.md), [docs/DECISIONS.md](DECISIONS.md),
[docs/ARCHITECTURE_SUMMARY.md](ARCHITECTURE_SUMMARY.md), and [docs/CURRENT_TASK.md](CURRENT_TASK.md)
have already drifted out of sync with the code once before (they're all dated 2026-06-20 and
describe the JsonNode/DTO fix as the latest state, even though the human-approval hardening
work landed afterward) — this workflow is how that doesn't keep happening.

## The four files and their distinct jobs

Each file answers a different question. Don't blur them — that's what causes duplication and
makes the set hard to trust.

| File | Answers | Lifespan |
|---|---|---|
| [PROJECT_MEMORY.md](PROJECT_MEMORY.md) | "What's true about this system right now?" — design principles, fixed bugs, operational patterns, scaffolding inventory | Accumulates, gets compressed |
| [DECISIONS.md](DECISIONS.md) | "Why does it work this way, and what did we reject?" — one entry per architectural decision, with rationale and alternatives | Append-only, rarely edited once written |
| [ARCHITECTURE_SUMMARY.md](ARCHITECTURE_SUMMARY.md) | "What does the system look like today?" — service topology, data flow, config table | Overwritten in place, no history |
| [CURRENT_TASK.md](CURRENT_TASK.md) | "What just happened, and what's next?" — latest session's diffs, known issues, next priorities | Replaced each session, not accumulated |

[CLAUDE.md](../CLAUDE.md) is a fifth, related-but-separate file: it's the always-loaded
onboarding reference for *how to work in the codebase* (commands, file paths, conventions). It
should already reflect current architecture as a side effect of normal development — this
workflow is about the four *memory* files above, which are read on demand, not auto-loaded.

## What triggers an update

Run this workflow after any change that fits one of these buckets — not after routine bugfixes,
typo fixes, or refactors that don't change a contract:

- A new architectural seam or pattern (e.g., a new single-entry-point service, a new DTO
  pattern, a new cross-service contract).
- A change to how an existing seam behaves (e.g., adding a conditional edge to the LangGraph
  workflow, adding a guard that changes valid state transitions).
- A deployment/infra change (new env var with runtime effect, new external dependency, new
  Docker service, a change to which Postgres endpoint form is required).
- Resolution of a P0/P1 bug whose root cause reveals a pattern worth remembering (not every bug
  — only ones where the *lesson* generalizes beyond the one call site).
- Wiring up something previously listed as scaffolding (moves an entry off the "Provisioned-but-unused" list).

If none of these apply, skip the workflow — don't pad these files with routine diffs.

## The six steps

### 1. Update PROJECT_MEMORY.md
- Add the new fact under the relevant existing section (Design Principles, Database Decisions,
  Operational Patterns, Known Scaffolding) rather than creating a new section per change.
- If it's a bug fix worth remembering, add it under a `## Critical Fixes` entry using the
  existing Symptom / Root Cause / Fix / Pattern / Files shape — the pattern line is what makes
  the entry reusable later, write it as a rule, not a description of the one-off fix.
- Move anything superseded into the compression step (5) rather than leaving two conflicting
  statements in the file.

### 2. Update DECISIONS.md
- Only add an entry if this is a *new* decision or it *reverses* an existing one.
- Follow the existing shape per entry: **Decision** / **Rationale** / **Constraints** /
  **Alternative(s) Rejected**.
- If a change reverses a prior decision (e.g., "no conditional edges" → conditional edge added
  after `human_approval`), don't delete the old entry — append a note under it (`**Superseded
  by**: ...`) so the historical rationale for the original choice isn't lost. DECISIONS.md is
  append-only for this reason.

### 3. Update ARCHITECTURE_SUMMARY.md — only if the change is visible at the summary level
- This file is a snapshot, not a log. Update the relevant table/section in place; don't append
  a changelog entry.
- Skip this step if the change is internal to a service and wouldn't change how you'd describe
  the system's shape to someone seeing it for the first time. Most single-file fixes don't
  warrant a summary update — most new seams or topology changes do.

### 4. Update CURRENT_TASK.md
- Replace the "Latest Session Summary" section (don't keep stacking old sessions under it —
  that's what step 5 prevents). Carry forward only the still-open items: `Known Issues`,
  `Next Priorities`, `Deployment Readiness` blockers.
- Update `**Last updated**` date and, if it changed, `**Branch**`.
- Move anything resolved this session out of `Known Issues` / `Next Priorities` and, if it's
  worth remembering long-term, into PROJECT_MEMORY.md (step 1) before deleting it here —
  CURRENT_TASK.md is the most disposable of the four files.

### 5. Compress obsolete information
- In PROJECT_MEMORY.md: once a `## Critical Fixes` entry's pattern has been folded into
  `## Design Principles` (i.e., the rule is now general, not tied to the original symptom),
  delete the original entry — keep the principle, drop the incident narrative.
- In CURRENT_TASK.md: delete prior sessions' "Session Notes" / "Latest Session Summary" once
  their durable lessons have been promoted to PROJECT_MEMORY.md or DECISIONS.md. Don't let this
  file grow into a session log.
- In ARCHITECTURE_SUMMARY.md: when a scaffolding item moves from "not integrated" to
  "integrated," delete it from the ❌ list and fold it into the relevant ✅ section instead of
  listing it in both places.
- Rule of thumb: each file should describe *current* state plus *durable* lessons. If a fact is
  no longer true, or its lesson has already been generalized elsewhere, remove it — don't mark
  it "(OLD)" and leave it in place.

### 6. Keep documentation concise
- Prefer extending an existing bullet/row over adding a new subsection.
- One file, one purpose (see the table above) — if you're tempted to explain *why* in
  PROJECT_MEMORY.md or *what's true now* in DECISIONS.md, put it in the right file instead.
- Cap incident-specific writeups (like `P0_WORKFLOW_FIX.md`, `EXECUTIVE_SUMMARY.md`,
  `ACTION_ITEMS.md`) to the incident itself — once it's resolved and its lesson is folded into
  PROJECT_MEMORY.md / DECISIONS.md, the incident doc can be deleted or left as a historical
  record outside the four core files; don't keep updating it as if it were live memory.

## Quick checklist

```
[ ] Does this change meet a trigger condition above? If not, stop here.
[ ] PROJECT_MEMORY.md   — fact added/updated under the right existing section
[ ] DECISIONS.md        — new entry only if decision is new or reversed; old entries annotated, not deleted
[ ] ARCHITECTURE_SUMMARY.md — updated only if the system's shape, as described, actually changed
[ ] CURRENT_TASK.md     — latest session section replaced; durable items promoted before deletion
[ ] Obsolete content compressed/removed from all four files touched above
[ ] Last-updated date refreshed in CURRENT_TASK.md
```
