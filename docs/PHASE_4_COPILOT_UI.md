# Phase 4 — Copilot UI (4.10)

## Today (already strong — this is a deepening, not a build)

[CopilotPanel.tsx](../frontend/src/components/copilot/CopilotPanel.tsx) is a persistent right-rail,
collapsible on desktop, slide-over drawer on mobile. It already:

- Streams tokens over SSE via `streamCopilot` → `POST /api/copilot/stream` (onMeta/onDelta/onError/
  onDone), with an animated avatar state machine (thinking/processing/success/error).
- Is **page-context-aware**: [copilotActions.ts](../frontend/src/components/copilot/copilotActions.ts)
  maps `pathname → { page, label, quick actions }`. Sends `page` + `action` so the backend
  `CopilotSkillRouter` picks the right skill.
- Persists conversations (`GET /api/copilot/conversations`, `/{id}/messages`), supports new-chat and
  a history overlay.
- Renders assistant markdown, shows quick-action chips, aborts in-flight streams on unmount/new-chat.

## The gap: context + action coverage

Backend `CopilotSkillRouter` routes 10 skills, but the client only offers actions on 4 pages
(resume, jobs, applications, workflow) and **no** actions on dashboard/admin/recommendations/career.
Phase 4.10 = extend `pageConfigForPath` to cover the new/uncovered surfaces and add the spec's
capabilities as quick actions.

## Target `pageConfigForPath` map

| Route | `page` | Quick actions (key → label) |
|---|---|---|
| `/` | `dashboard` | `career_advice`→"Career advice", `market_trends`→"Market trends" |
| `/resumes*` | `resume` | `improve_resume`, `ats_analysis`, `suggest_skills`→"Suggest skills" (N) |
| `/jobs` | `jobs` | `job_matching`, `job_explanation`, `suggest_applications`→"What should I apply to?" (N) |
| `/recommendations` (N) | `recommendations` | `explain_recommendation`→"Explain this rec" (N), `explain_rejection`→"Why was this rejected?" (N) |
| `/applications` | `applications` | `followup`, `interview_prediction` |
| `/workflow` | `workflow` | `explain_results`, `explain_failures` |
| `/career` (N) | `career` | `career_advice`, `market_trends` |
| `/admin` | `dashboard`/`admin` | (none, or ops-focused) |

Capabilities requested by the spec, mapped:

| Spec capability | Action key | Backing skill |
|---|---|---|
| Explain recommendation | `explain_recommendation` | recommendation-explanation skill |
| Explain rejection | `explain_rejection` | (new skill or reuse recommendation handler with a "rejection" prompt) |
| Suggest resume changes | `improve_resume` | existing resume skill |
| Suggest skills | `suggest_skills` | existing/added skill |
| Suggest applications | `suggest_applications` | recommendation skill |
| Explain workflow | `explain_results` / `explain_failures` | existing workflow skill |
| Show career advice | `career_advice` | new/career skill |
| Show market trends | `market_trends` | new/market skill |

## Backend touch — the one permitted, optional change

Where a capability has **no** existing `CopilotSkill`, adding it follows the documented recipe: new
`CopilotSkill` enum value + `CopilotSkillHandler` impl under `service/copilot/skill/` + wire into
`CopilotSkillRouter`. This is **not** a recommendation/2E/3A contract change, so it is allowed — but
it is **optional and deferred**: the default Phase-4 plan wires only actions whose skills already
exist, and any *new* skill is a small, isolated follow-up PR. If a client action is sent with an
`action` the router doesn't recognize, the router already falls back to `GeneralAssistantHandler`, so
new client actions **degrade gracefully** even before a matching skill exists.

## Deep-linking context (recommendation / job)

For "Explain this recommendation/rejection", the card passes a `contextId` (the job/rec id) into the
existing `send(text, action)` → `streamCopilot({ ..., contextId })` path (the stream payload already
carries `contextId`, currently always `null`). No new plumbing — populate the field that already
exists. The backend handler assembles RAG context from that id via `CareerContextRetriever`.

## UX rules (unchanged)

- Actions only appear when relevant to the current page; the composer is always available for free
  text.
- One in-flight stream at a time; new-chat/route-away aborts it.
- Errors settle the avatar to `error` for ~2.6s then back to `ready`; never a hard crash.
- Copilot remains **refetch-free of page state** — it learns context from the URL, not shared stores,
  preserving the loose coupling the app relies on.

## Explicitly unchanged

The SSE transport, streaming helpers (`appendToLast`/`markLastDone`), avatar component, history
overlay, mobile drawer, and collapse rail are all retained. 4.10 edits **one file**
(`copilotActions.ts`) for the default plan, plus optional new backend skill handlers.
