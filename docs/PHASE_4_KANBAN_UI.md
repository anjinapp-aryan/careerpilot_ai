# Phase 4 — Kanban UI (4.7)

## Today

[Applications.tsx](../frontend/src/pages/Applications.tsx) already implements a dnd-kit Kanban with
**5 columns** and optimistic move (`PATCH /api/applications/{id}`), plus a Phase-3A lifecycle drawer
(`ApplicationDetailDialog`). Cards show company, role, match/ATS badges. This is a solid base — 4.7
**extends** it, not replaces it.

Current columns: `SAVED · APPLIED · INTERVIEWING · OFFER · REJECTED`.

## Backend status vocabulary (verified)

`Application.status` (core entity) = **`SAVED | APPLIED | INTERVIEWING | OFFER | REJECTED |
WITHDRAWN`** (6 states). The Phase 3A **lifecycle read-model** has a much richer state set (DRAFT,
SUBMITTED, VIEWED, UNDER_REVIEW, ASSESSMENT, …, ACCEPTED, WITHDRAWN, EXPIRED) but that is a separate,
dark, read-only projection — the drag-and-drop board writes to the **core** `Application.status`.

## Target: 8 columns (spec) — reconciled with the backend

The spec asks for: `Saved · Applied · Viewed · Interview · Offer · Rejected · Withdrawn · Archived`.
Reconciliation:

| Spec column | Backing status | How |
|---|---|---|
| Saved | `SAVED` | existing |
| Applied | `APPLIED` | existing |
| **Viewed** | *(none in core)* | **Read-only** column populated from the Phase 3A lifecycle `VIEWED` state when the workflow engine is enabled; empty/hidden when dark. Not a drop target that writes core status (no `VIEWED` in core enum). |
| Interview | `INTERVIEWING` | existing (relabel to "Interview") |
| Offer | `OFFER` | existing |
| Rejected | `REJECTED` | existing |
| **Withdrawn** | `WITHDRAWN` | **new column**; core status already supports it — full drop target |
| **Archived** | *(none in core)* | **client-side archive** — either a UI-only hidden flag or, if the recommendation `archive` semantics apply, kept out of scope; render as a collapsed column that is empty until backend support exists |

**Decision:** ship **7 write-columns** the core enum supports (Saved, Applied, Interview, Offer,
Rejected, Withdrawn) + render **Viewed** and **Archived** as **read-only / degraded** columns that
only populate when their backing data exists. This honors "no backend change" while presenting the
8-column layout the spec wants. Document the two non-writable columns in-UI with a subtle hint so
users don't expect to drag into them on a dark stack.

> If, at implementation, product wants Viewed/Archived to be first-class writable statuses, that is a
> **backend enum change** and therefore **out of Phase 4 scope** — flag it as a follow-up, do not
> smuggle it in.

## Column config (target)

```ts
const COLUMNS = [
  { id: 'SAVED',        label: 'Saved',     writable: true,  tone: 'neutral' },
  { id: 'APPLIED',      label: 'Applied',   writable: true,  tone: 'info' },
  { id: 'VIEWED',       label: 'Viewed',    writable: false, tone: 'info',    source: 'lifecycle' },
  { id: 'INTERVIEWING', label: 'Interview', writable: true,  tone: 'primary' },
  { id: 'OFFER',        label: 'Offer',     writable: true,  tone: 'success' },
  { id: 'REJECTED',     label: 'Rejected',  writable: true,  tone: 'danger' },
  { id: 'WITHDRAWN',    label: 'Withdrawn', writable: true,  tone: 'neutral' },
  { id: 'ARCHIVED',     label: 'Archived',  writable: false, tone: 'neutral', source: 'client' },
];
```

`onDragEnd` guards moves into non-writable columns (drop is rejected, card snaps back) — a small
extension of the existing `COLUMNS.some(c => c.id === target)` check to also require `writable`.

## Card metadata (spec: Company · Role · Date · Resume Version · Workflow Status · Career Probability)

`AppCard` today shows company/role + match/ATS badges. Add (all dark-tolerant, conditional):

| Field | Source |
|---|---|
| Date | `application.createdAt` (already available) |
| Resume Version | join to the resume version used, if the application carries `resumeVersionId`; else omit |
| Workflow Status | `GET /api/workflow/applications/{jobId}/lifecycle` → `currentStatus` (already fetched by the drawer; lift to a lightweight per-card badge query, `retry:false`, or reuse a batched lifecycle fetch) |
| Career Probability | `GET /api/workflow/career-intelligence` offer/interview probability for the role, if present; else omit |

To avoid N per-card requests for lifecycle/probability, prefer **one page-level query** each and map
by `jobId` client-side (the page already loads all applications + a jobs map). Only fall back to
per-card queries if no batch endpoint exists.

## Responsiveness

8 columns overflow narrow screens. Keep the existing responsive grid but switch to a horizontally
scrollable column strip (`overflow-x-auto`) below `xl`, so columns keep a usable min-width instead of
crushing to 2-up. Cards and dnd behavior are unchanged.

## Unchanged

Optimistic `move` mutation (snapshot + rollback), the lifecycle drawer, sensors, drag overlay — all
retained. 4.7 = +3 columns (2 read-only) +4 card fields + a writable-column guard.
