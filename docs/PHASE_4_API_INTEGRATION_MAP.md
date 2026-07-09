# Phase 4 — API Integration Map

Every route below is **already live** in the backend (verified against the controller sources). "🌑"
marks endpoints that are **dark by default** (feature-flagged off → return empty/404/403); their UI
must be dark-tolerant. No new backend endpoint is required for Phase 4.

## Auth & session (existing, unchanged)

| Method | Path | Controller | UI |
|---|---|---|---|
| POST | `/api/auth/register` | AuthController | Register |
| POST | `/api/auth/login` | AuthController | Login |

## 4.1 Dashboard

| Method | Path | 🌑 | Widget |
|---|---|---|---|
| GET | `/api/dashboard` | | KPI grid, charts, recent runs |
| GET | `/api/recommendations?size=1` | 🌑 | Recommendations count card |
| GET | `/api/recommendations/must-apply?size=1` | 🌑 | Must-apply count |
| GET | `/api/recommendations/human-review` | 🌑 | **Pending Approvals** widget (N) |
| GET | `/api/jobs/recommended?filter=new` | | **Today's Opportunities** widget (N) |
| GET | `/api/jobs/discovery/lake/status` | | Job Discovery card |
| GET | `/api/diagnostics/observability` | | Platform Health card |
| GET | `/api/workflow/career-intelligence` | 🌑 | Career Intelligence card + **probability trend** (N) |
| GET | `/api/workflow/interviews` | 🌑 | **Upcoming Interviews** widget (N) |
| GET | `/api/resume/tailored/history` | 🌑 | **Recent Resume Improvements** widget (N) |

## 4.2 Resume

| Method | Path | 🌑 | Use |
|---|---|---|---|
| GET | `/api/resumes` | | Library list |
| POST | `/api/resumes` (multipart) | | Upload |
| GET | `/api/resumes/{id}/versions` | | ATS score history strip |
| GET | `/api/resumes/{id}/versions/{vid}/download` | | DOCX/TXT export |
| POST | `/api/resume/tailor` | 🌑 | Tailoring pipeline start (N) |
| GET | `/api/resume/tailor/jobs/{jobId}` | 🌑 | Tailoring job status (N) |
| GET | `/api/resume/tailored` · `/tailored/history` | 🌑 | Tailored versions + audit (N) |
| POST | `/api/resume/ats/analyze` | 🌑 | ATS optimization (N) |
| GET | `/api/resume/ats/latest` · `/history` | 🌑 | ATS score history (N) |
| GET | `/api/diagnostics/gap-analysis` | 🌑 | Gap-analysis stage status (N) |

## 4.3 / 4.4 Jobs + Explainability

| Method | Path | 🌑 | Use |
|---|---|---|---|
| GET | `/api/jobs` · `/api/jobs/{id}` | | Browse / detail |
| POST | `/api/jobs` | | Add manual job |
| GET | `/api/jobs/recommended?filter=&page=&size=` | 🌑 | Recommended tab |
| GET | `/api/jobs/discovered?scope=domestic\|international&country=` | 🌑 | Domestic/International tabs |
| GET | `/api/jobs/pool` | 🌑 | Browse "more opportunities" |
| GET | `/api/jobs/search/semantic?q=&k=` | 🌑 | Smart search |
| GET | `/api/jobs/{id}/enrichment` | 🌑 | AI insights panel |
| POST | `/api/jobs/{id}/explain` | | `ExplainDialog` (match breakdown) |
| **GET** | **`/api/jobs/{id}/relevance`** | 🌑 | **RelevanceDrawer (N) — `{score, strength, visible, reasons}`** |
| POST | `/api/jobs/telemetry` | | Click telemetry (existing) |

> **4.3 card badges** (Career Match %, Match Strength, Priority, Must-Apply) source from the
> recommendation payload (`/api/jobs/recommended`, `/api/recommendations`) and the relevance
> endpoint. `types/workflow.ts` gains additive fields: `relevanceScore?`, `matchStrength?`
> (`'EXCELLENT'|'STRONG'|'GOOD'|'WEAK'|'HIDDEN'`), `visible?`, `priority?`, `mustApply?`.

## 4.5 Recommendations (RecommendationController — all 🌑)

| Method | Path | Use |
|---|---|---|
| GET | `/api/recommendations?priority=&page=&size=` | Priority tabs (Critical/High/Medium/Low) |
| GET | `/api/recommendations/must-apply` | Must-apply strip |
| GET | `/api/recommendations/human-review` | Approval queue |
| GET | `/api/recommendations/audit` | Archived tab / history |
| POST | `/api/recommendations/approve` | Approve action |
| POST | `/api/recommendations/reject` | Reject action |
| POST | `/api/recommendations/save` | Save / Apply-Later |
| POST | `/api/recommendations/archive` | Archive action |
| POST | `/api/recommendations/feedback` · GET `/feedback` | Behavior learning signal |
| GET | `/api/recommendations/behavior-profile` | Behavior signal chip; skill-gap chart (4.8) |

> "Critical/High/Medium/Low/Archived" tabs map to the `priority` query param + the `/audit`
> (archived) feed. If the backend exposes priority as an enum on the rec payload rather than a query
> filter, the UI filters client-side — verify the exact param at implementation time.

## 4.6 Workflow correlation (WorkflowTraceController — all 🌑)

| Method | Path | View |
|---|---|---|
| GET | `/api/workflow/correlation/{id}` | Timeline |
| GET | `/api/workflow/correlation/{id}/summary` | Header summary |
| GET | `/api/workflow/correlation/{id}/graph` | Graph |
| GET | `/api/workflow/correlation/{id}/events` | Raw events |
| GET | `/api/diagnostics/workflow-dead-letter` | Dead-letter view |
| GET | `/api/diagnostics/workflow-correlation` | Correlation engine health |

Existing LangGraph path (kept): `POST /api/workflows/run`, `POST /api/workflows/{threadId}/resume`,
`GET /api/workflows/{threadId}`, `GET /api/workflows`.

## 4.7 Applications

| Method | Path | 🌑 | Use |
|---|---|---|---|
| GET | `/api/applications` | | Kanban cards |
| POST | `/api/applications` | | Save/Apply |
| PATCH | `/api/applications/{id}` | | Column move (status change) |
| GET | `/api/workflow/applications/{jobId}/lifecycle` | 🌑 | Card workflow status, drawer |
| GET | `/api/workflow/applications/{jobId}/timeline` | 🌑 | Drawer event timeline |

## 4.8 Career Intelligence (all 🌑)

| Method | Path | Chart |
|---|---|---|
| GET | `/api/workflow/career-intelligence` | Success/Interview/Offer probabilities |
| GET | `/api/workflow/analytics` | Role trajectory, rates |
| GET | `/api/recommendations/behavior-profile` | Skill gaps |
| GET | `/api/admin/stats/salary-intelligence` | Salary growth (already public-ish/admin) |
| GET | `/api/admin/stats/skill-heatmap` | Market demand |

## 4.9 Admin (diagnostics — mostly no-auth)

| Path | Panel |
|---|---|
| `/api/admin/stats/{provider-health,discovery,skill-heatmap,salary-intelligence,duplicates,enrichment-metrics}` | existing panels |
| `/api/diagnostics/observability` | rollup (existing) |
| `/api/diagnostics/{resume-tailoring,ats-optimization}/queue` · `/health` | Queue health (N) |
| `/api/diagnostics/{gap-analysis,ats-explainability,cover-letter,application-package,auto-apply-package}` | Pipeline stage health (N) |
| `/api/diagnostics/match-cache` | Cache health (N) |
| `/api/diagnostics/{application-tracking,application-timeline,email-intelligence,interview-tracking,application-analytics,career-intelligence}` | Workflow-engine stage health (N) |
| `/api/execution/tracking` · `/api/diagnostics/application-execution` · `/browser` · `/ats` | Execution health (N) |
| `/api/admin/retention/status` · POST `/run` | Retention (existing) |

## 4.10 Copilot (existing SSE)

| Method | Path | Use |
|---|---|---|
| POST | `/api/copilot/stream` (SSE) | Streamed chat; `page`+`action` drive skill routing |
| GET | `/api/copilot/conversations` · `/{id}/messages` | History |
| DELETE | `/api/copilot/conversations/{id}` | Delete conversation |

New Copilot **actions** (client keys in `copilotActions.ts`) map to existing/added `CopilotSkill`
values: `explain_rejection`, `suggest_skills`, `suggest_applications`, `market_trends`,
`career_advice`. Adding a *new* skill is the only optional backend change in Phase 4 and follows the
documented "new `CopilotSkill` + handler + router wiring" recipe — it does not touch recommendation,
2E, or 3A contracts.

## Multi-tenant note

Every authenticated endpoint already enforces `userId.equals(...)` manually server-side. Phase 4
adds no new endpoint, so no new tenant-isolation surface is introduced. The frontend continues to
send only the bearer token; it never sends `userId` in bodies.
