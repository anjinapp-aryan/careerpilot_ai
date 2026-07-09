# Phase 4 — Routing

Router: `react-router-dom` 6.27, declared in [App.tsx](../frontend/src/App.tsx). Nested routes render
into the `AppShell` `<Outlet/>`. Guards: `Private` (token present) and `AdminOnly` (role ∈
{OWNER, ADMIN}). Phase 4 **adds two routes** and touches nothing else in the router.

## Target route tree

```tsx
<Routes>
  <Route path="/login" element={<Login/>} />
  <Route path="/register" element={<Register/>} />

  <Route path="/" element={<Private><AppShell/></Private>}>
    <Route index element={<Dashboard/>} />
    <Route path="resumes" element={<Resumes/>} />
    <Route path="resumes/:id/optimize" element={<ResumeOptimization/>} />
    <Route path="jobs" element={<Jobs/>} />
    <Route path="recommendations" element={<Recommendations/>} />        {/* NEW 4.5 */}
    <Route path="applications" element={<Applications/>} />
    <Route path="workflow" element={<Workflow/>} />
    <Route path="career" element={<CareerIntelligence/>} />              {/* NEW 4.8 */}
    <Route path="admin" element={<AdminOnly><AdminDashboard/></AdminOnly>} />
  </Route>

  <Route path="*" element={<Navigate to="/" replace/>} />
</Routes>
```

## New routes

| Path | Component | Guard | Nav group | Notes |
|---|---|---|---|---|
| `/recommendations` | `Recommendations` | `Private` | Career | Priority tabs are **in-page state**, not sub-routes (mirrors Jobs tabs) |
| `/career` | `CareerIntelligence` | `Private` | AI | Charts page; dark-tolerant |

## In-page tabbing (not routes)

Consistent with the existing app, tabbed surfaces use component state + the `Tabs` primitive, **not**
nested routes or query params:

- Jobs: `recommended|domestic|international|saved|applied|browse` (existing `useState`).
- Recommendations (N): `critical|high|medium|low|archived` (Zustand `useRecommendationsUi` so the tab
  survives a Copilot-driven remount).
- Workflow → Correlation Explorer (N): `timeline|graph|events|deadletter` (component state).

Rationale: the app has no deep-linking-into-tab requirement today; keeping tabs as state avoids
router churn and matches every existing multi-tab page. If shareable tab URLs are later wanted, they
become `?tab=` search params without restructuring routes.

## Guard behavior (unchanged)

- **Unauthenticated** hitting any `/` child → `Private` redirects to `/login`.
- **Non-admin** hitting `/admin` → `AdminOnly` redirects to `/`. (Defense-in-depth; backend also
  403s.) `/recommendations` and `/career` are **not** admin-gated — they are candidate-facing.
- **Unknown path** → redirect to `/`.

## Command palette & breadcrumbs

- [CommandPalette](../frontend/src/components/app-shell/CommandPalette.tsx) is driven by `NAV_ITEMS`
  (from `nav.ts`); adding the two nav entries makes them searchable automatically — no palette code
  change.
- [Breadcrumbs](../frontend/src/components/app-shell/Breadcrumbs.tsx) use `labelForPath()` in
  `nav.ts`; the new nav entries give correct labels for `/recommendations` and `/career`.

## Lazy loading (optional, recommended for the two large new pages)

The app currently imports pages eagerly. Phase 4 may introduce `React.lazy` + `Suspense` for
`Recommendations` and `CareerIntelligence` (both chart/data-heavy) to keep the initial bundle lean.
This is an isolated, reversible optimization — not required for correctness.
