# Phase 4 — Screen Map (complete inventory)

`E` = exists today · `X` = extend · `N` = new in Phase 4.

## Public (unauthenticated)

| Route | Screen | File | State |
|---|---|---|---|
| `/login` | Login | [pages/Login.tsx](../frontend/src/pages/Login.tsx) | E |
| `/register` | Register | [pages/Register.tsx](../frontend/src/pages/Register.tsx) | E |

## Authenticated (inside `AppShell` outlet)

| Route | Screen | File | State | Phase-4 change |
|---|---|---|---|---|
| `/` | Executive Dashboard | [pages/Dashboard.tsx](../frontend/src/pages/Dashboard.tsx) | X | +3 KPIs, +5 widgets (4.1) |
| `/resumes` | Resume Library | [pages/Resumes.tsx](../frontend/src/pages/Resumes.tsx) | X | +tailoring/gap pipeline entry (4.2) |
| `/resumes/:id/optimize` | Resume Optimization Center | [pages/ResumeOptimization.tsx](../frontend/src/pages/ResumeOptimization.tsx) | X | +ATS history, +tailoring audit, +visual pipeline (4.2) |
| `/jobs` | Job Discovery Workspace | [pages/Jobs.tsx](../frontend/src/pages/Jobs.tsx) | X | +match-strength/priority/must-apply badges, +relevance drawer (4.3/4.4) |
| `/applications` | Application Workspace (Kanban) | [pages/Applications.tsx](../frontend/src/pages/Applications.tsx) | X | 5→8 columns, +card metadata (4.7) |
| `/recommendations` | **Recommendation Workspace** | `pages/Recommendations.tsx` | **N** | priority tabs + lifecycle actions (4.5) |
| `/workflow` | AI Workflow Workspace | [pages/Workflow.tsx](../frontend/src/pages/Workflow.tsx) | X | +Timeline/Graph/Event/DLQ views (4.6) |
| `/career` | **Career Intelligence Workspace** | `pages/CareerIntelligence.tsx` | **N** | probability/market/skill charts (4.8) |
| `/admin` | Admin Workspace | [pages/AdminDashboard.tsx](../frontend/src/pages/AdminDashboard.tsx) | X | +queue/execution/cache/pipeline health (4.9) |

Right rail on every authenticated screen: **AI Copilot** ([CopilotPanel.tsx](../frontend/src/components/copilot/CopilotPanel.tsx)) — X, +contexts/actions (4.10).

## Sidebar nav (target)

Current groups in [nav.ts](../frontend/src/components/app-shell/nav.ts): Overview(Dashboard),
Career(Resumes, Jobs, Applications), AI(AI Workflow), Admin(Admin). Phase 4 adds:

```
Overview   → Dashboard
Career     → Resumes · Jobs · Recommendations(N) · Applications
AI         → AI Workflow · Career Intelligence(N)
Admin      → Admin Dashboard        (adminOnly)
```

Two new nav entries (`/recommendations`, `/career`); order preserves the existing grouping. Copilot
is not a nav item (it is the persistent rail).

## Modal / drawer inventory

| Surface | Trigger | State | Backend |
|---|---|---|---|
| Add-job dialog | Jobs → Add job | E | `POST /api/jobs` |
| Preferences dialog | Jobs / Domestic → Preferences | E | `GET/PUT /api/candidate/preferences` |
| Match explanation (`ExplainDialog`) | Recommended card → Why am I a match? | E | `POST /api/jobs/{id}/explain` |
| **Relevance drawer** | Job card → Why am I seeing this? | **N** | `GET /api/jobs/{id}/relevance` |
| Application workflow drawer | Kanban card → info | E | `GET /api/workflow/applications/{jobId}/lifecycle` + `/timeline` |
| Command palette | ⌘K | E | client-only nav |
| Copilot history overlay | Copilot → history | E | `GET /api/copilot/conversations` |
